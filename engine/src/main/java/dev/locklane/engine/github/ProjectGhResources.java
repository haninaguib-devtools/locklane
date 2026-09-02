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

    /** Diffs each project's cache against its previous state and publishes `issuesChanged` (#129) where it moved. */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = REFRESH_INTERVAL_MS)
    void refreshAll() {
        contexts.forEach((projectId, context) -> {
            try {
                if (context.cache().refresh()) {
                    eventBroadcaster.broadcast("issuesChanged", Map.of("projectId", projectId));
                }
            } catch (RuntimeException e) {
                log.error("Scheduled issue/PR refresh failed for project {}", projectId, e);
            }
        });
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
