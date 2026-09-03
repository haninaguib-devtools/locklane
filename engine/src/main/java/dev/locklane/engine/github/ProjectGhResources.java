package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.ProjectStatus;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.ws.EventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Builds and caches one {@link ProjectGhContext} per project (#81) — each with its
 * own {@link CliGhClient} scoped to that project's own workarea and (if stored) its
 * own decrypted token, replacing the single shared {@code GhClient} bean every
 * project used to fetch through. Refreshed on the same schedule {@link GhIssueCache}
 * used to refresh itself before it stopped being a singleton bean.
 */
@Service
public class ProjectGhResources {

    private static final Logger log = LoggerFactory.getLogger(ProjectGhResources.class);
    private static final long REFRESH_INTERVAL_MS = 30_000;

    private final ProjectRepository projectRepository;
    private final GhAccountRepository ghAccountRepository;
    private final TokenCipher tokenCipher;
    private final EventBroadcaster eventBroadcaster;
    private final BiFunction<Path, String, GhClient> clientFactory;
    private final Map<Long, ProjectGhContext> contexts = new ConcurrentHashMap<>();
    private volatile CredentialRenewer credentialRenewer = CredentialRenewer.NONE;

    @Autowired
    public ProjectGhResources(ProjectRepository projectRepository, GhAccountRepository ghAccountRepository,
            TokenCipher tokenCipher, EventBroadcaster eventBroadcaster) {
        this(projectRepository, ghAccountRepository, tokenCipher, eventBroadcaster, CliGhClient::new);
    }

    /**
     * Test-only: swaps in a fake client instead of shelling out to a real gh
     * process, and a broadcaster with no registered sessions since these tests
     * don't care about the events channel. Public so tests outside this package
     * (e.g. persistence's {@code WorktreeCreationServiceTest}) can build one
     * directly rather than needing a real {@code gh} on PATH.
     */
    public ProjectGhResources(ProjectRepository projectRepository, GhAccountRepository ghAccountRepository,
            TokenCipher tokenCipher, BiFunction<Path, String, GhClient> clientFactory) {
        this(projectRepository, ghAccountRepository, tokenCipher, new EventBroadcaster(new ObjectMapper()),
                clientFactory);
    }

    public ProjectGhResources(ProjectRepository projectRepository, GhAccountRepository ghAccountRepository,
            TokenCipher tokenCipher, EventBroadcaster eventBroadcaster, BiFunction<Path, String, GhClient> clientFactory) {
        this.projectRepository = projectRepository;
        this.ghAccountRepository = ghAccountRepository;
        this.tokenCipher = tokenCipher;
        this.eventBroadcaster = eventBroadcaster;
        this.clientFactory = clientFactory;
    }

    /**
     * Empty for an unknown project id — never resolves any other project's context.
     * A {@link ProjectStatus#FAILED} project (#569) gets a context that answers with
     * no issues and no PRs, never cached and never refreshed: its clone did not
     * complete, so its workarea directory does not exist and running {@code gh} there
     * could only fail with a misleading "is gh installed" warning that hid the real
     * clone error. Not caching it means a successful retry sees a real context on
     * the next lookup.
     */
    public Optional<ProjectGhContext> forProject(long projectId) {
        ProjectGhContext existing = contexts.get(projectId);
        if (existing != null) {
            return Optional.of(existing);
        }
        return projectRepository.findById(projectId)
                .map(project -> project.status() == ProjectStatus.FAILED
                        ? buildWithoutCheckout(project)
                        : contexts.computeIfAbsent(projectId, id -> build(project)));
    }

    /** Forces the next lookup to rebuild the client/cache — e.g. after the stored token changes (#81). */
    public void evict(long projectId) {
        contexts.remove(projectId);
    }

    /**
     * Registers who to ask when a project's fetch fails with {@code Bad credentials}
     * (#656). {@link GhTokenRenewalService} registers itself at construction; with
     * nothing registered a 401 is reported exactly as before, and nothing retries.
     * Kept as a hook rather than a constructor dependency because the renewer needs
     * {@link #evict} — a constructor dependency both ways would be a bean cycle.
     */
    public void onBadCredentials(CredentialRenewer renewer) {
        this.credentialRenewer = renewer == null ? CredentialRenewer.NONE : renewer;
    }

    /**
     * Diffs each project's cache against its previous state and publishes
     * `issuesChanged` (#129) where it moved, and `githubRefreshStatus` (#619) where
     * the fetch's outcome moved -- started failing, stopped failing, or failing with
     * different text -- so the sidenav learns that GitHub is unreachable without
     * anyone clicking anything.
     *
     * <p>A fetch that fails with {@code Bad credentials} asks the registered
     * {@link CredentialRenewer} for one renewal (#656); if it got one, the project's
     * context is rebuilt with the renewed token and fetched once more in the same
     * tick, so a renewed account recovers here rather than a poll later. A retry that
     * still answers 401 tells the renewer, which marks the account as needing
     * reconnection so nothing retries it again.
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = REFRESH_INTERVAL_MS)
    void refreshAll() {
        // A snapshot: a renewal below evicts and rebuilds entries while we iterate.
        for (Map.Entry<Long, ProjectGhContext> entry : List.copyOf(contexts.entrySet())) {
            long projectId = entry.getKey();
            ProjectGhContext context = entry.getValue();
            try {
                GhRefreshStatus before = context.cache().status();
                boolean changed = context.cache().refresh();
                GhRefreshStatus after = context.cache().status();
                if (isBadCredentials(after) && credentialRenewer.renewForProject(projectId)) {
                    Optional<ProjectGhContext> rebuilt = forProject(projectId);
                    if (rebuilt.isPresent()) {
                        changed = rebuilt.get().cache().refresh();
                        after = rebuilt.get().cache().status();
                        if (isBadCredentials(after)) {
                            credentialRenewer.renewalDidNotHelp(projectId);
                        }
                    }
                }
                broadcastStatusIfMoved(eventBroadcaster, projectId, before, after);
                if (changed) {
                    eventBroadcaster.broadcast("issuesChanged", Map.of("projectId", projectId));
                }
            } catch (RuntimeException e) {
                log.error("Scheduled issue/PR refresh failed for project {}", projectId, e);
            }
        }
    }

    /** The failure text {@code CliGhClient} folds a rejected token's stderr into (#619): {@code gh exited 1: HTTP 401: Bad credentials ...}. */
    private static boolean isBadCredentials(GhRefreshStatus status) {
        return status.failing() && status.failure() != null && status.failure().contains("Bad credentials");
    }

    /**
     * What {@link #refreshAll} asks when a project's token is refused (#656). The
     * implementation is {@link GhTokenRenewalService}; the interface keeps this class
     * free of any dependency on it.
     */
    public interface CredentialRenewer {
        /** Does nothing and never authorizes a retry — the state before any renewer registers. */
        CredentialRenewer NONE = new CredentialRenewer() {
            @Override
            public boolean renewForProject(long projectId) {
                return false;
            }

            @Override
            public void renewalDidNotHelp(long projectId) {
            }
        };

        /** Renews the account behind {@code projectId} now; {@code true} when a fresh token is stored and the project evicted, so a retry is worth making. */
        boolean renewForProject(long projectId);

        /** The retry {@link #renewForProject} authorized was refused too: the account is done until a human reconnects it. */
        void renewalDidNotHelp(long projectId);
    }

    /**
     * Broadcasts {@code githubRefreshStatus} (#619) when the fetch outcome for a
     * project differs from what it was before the refresh. Shared with
     * {@link IssueController}'s forced refresh so both paths report the same way.
     * The payload mirrors {@link GhRefreshStatus}; {@code failure} and
     * {@code lastSuccessAt} are absent rather than null when unset, since
     * {@link Map#of} rejects null values.
     */
    static void broadcastStatusIfMoved(EventBroadcaster broadcaster, long projectId,
            GhRefreshStatus before, GhRefreshStatus after) {
        if (before.sameOutcomeAs(after)) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("projectId", projectId);
        fields.put("failing", after.failing());
        if (after.failure() != null) {
            fields.put("failure", after.failure());
        }
        if (after.lastSuccessAt() != null) {
            fields.put("lastSuccessAt", after.lastSuccessAt().toString());
        }
        broadcaster.broadcast("githubRefreshStatus", fields);
    }

    /** A context for a project with no checkout to run {@code gh} in (#569): empty issues, empty PRs. */
    private static ProjectGhContext buildWithoutCheckout(ProjectRecord project) {
        log.debug("Project {} is FAILED and has no checkout; serving an empty issue tree instead of running gh",
                project.id());
        GhClient client = new NoCheckoutGhClient();
        GhIssueCache cache = new GhIssueCache(client);
        IssueDetailService detailService = new IssueDetailService(cache, client, project.workareaPath().toString());
        IssueTreeService treeService = new IssueTreeService(cache);
        return new ProjectGhContext(client, cache, detailService, treeService);
    }

    /** Stands in for {@link CliGhClient} when there is no directory to run {@code gh} in (#569). */
    private static final class NoCheckoutGhClient implements GhClient {
        @Override
        public List<GhIssue> issues() {
            return List.of();
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }

    private ProjectGhContext build(ProjectRecord project) {
        String token = projectRepository.findGithubAccountId(project.id())
                .flatMap(ghAccountRepository::findEncryptedToken)
                .map(tokenCipher::decrypt)
                .orElse(null);
        GhClient client = clientFactory.apply(project.workareaPath(), token);
        GhIssueCache cache = new GhIssueCache(client);
        IssueDetailService detailService = new IssueDetailService(cache, client, project.workareaPath().toString());
        IssueTreeService treeService = new IssueTreeService(cache);
        return new ProjectGhContext(client, cache, detailService, treeService);
    }
}
