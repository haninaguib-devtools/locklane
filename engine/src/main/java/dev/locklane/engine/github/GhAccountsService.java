package dev.locklane.engine.github;

import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * GitHub accounts Locklane owns (#550): sign in through GitHub's OAuth device flow
 * or paste an existing token, list them, remove one. Replaces the host-{@code gh}-login
 * reads this class used to do for #532 — no {@code gh auth status}, no
 * {@code gh auth token --user}, no falling back to whatever the engine host's own
 * {@code gh} is logged in as.
 *
 * <p>A device flow in progress is tracked purely in memory ({@link #flows}), never
 * persisted: it is a short-lived handshake (GitHub's own {@code expires_in}, usually
 * 15 minutes) between "start" and either an account landing in
 * {@link GhAccountRepository} or the flow failing, and losing it on a restart is the
 * right behaviour — there is nothing to resume. {@link #startDeviceFlow} hands the
 * actual polling to {@code pollExecutor}, off the request thread, exactly as the
 * issue asks; {@link #pruneStaleFlows} keeps the map from growing forever.
 */
@Service
public class GhAccountsService {

    private static final Logger log = LoggerFactory.getLogger(GhAccountsService.class);

    /** Requested by the device flow (#550's own Done-when): push, workflow-file pushes (#531), and org membership reads. */
    private static final String DEVICE_FLOW_SCOPE = "repo workflow read:org";

    /** A pasted token (#550) must carry at least this — the same push-capability floor as #81 always assumed. */
    private static final String REQUIRED_TOKEN_SCOPE = "repo";

    /** How long a finished (complete/failed) flow is still answerable, for a client's very last poll. */
    private static final java.time.Duration FLOW_RETENTION_AFTER_FINISH = java.time.Duration.ofMinutes(5);

    private final GhAccountRepository accountRepository;
    private final ProjectRepository projectRepository;
    private final TokenCipher tokenCipher;
    private final GhTokenIntrospector introspector;
    private final GhDeviceFlow deviceFlow;
    private final Executor pollExecutor;
    private final Optional<String> oauthClientId;
    private final Map<String, DeviceFlowState> flows = new ConcurrentHashMap<>();

    @Autowired
    public GhAccountsService(GhAccountRepository accountRepository, ProjectRepository projectRepository,
            TokenCipher tokenCipher, GhTokenIntrospector introspector,
            @Qualifier("githubDeviceFlowExecutor") Executor pollExecutor,
            @Value("${locklane.github.oauth-client-id:}") String oauthClientId) {
        this(accountRepository, projectRepository, tokenCipher, introspector, new HttpGhDeviceFlow(), pollExecutor,
                oauthClientId);
    }

    /** Test-only: substitutes {@link GhDeviceFlow} so no real GitHub endpoint is ever reached. */
    GhAccountsService(GhAccountRepository accountRepository, ProjectRepository projectRepository,
            TokenCipher tokenCipher, GhTokenIntrospector introspector, GhDeviceFlow deviceFlow, Executor pollExecutor,
            String oauthClientId) {
        this.accountRepository = accountRepository;
        this.projectRepository = projectRepository;
        this.tokenCipher = tokenCipher;
        this.introspector = introspector;
        this.deviceFlow = deviceFlow;
        this.pollExecutor = pollExecutor;
        this.oauthClientId = (oauthClientId == null || oauthClientId.isBlank())
                ? Optional.empty() : Optional.of(oauthClientId.strip());
    }

    /** Every account {@code ownerUserId} added, newest first. */
    public List<GhAccount> accountsFor(long ownerUserId) {
        return accountRepository.findAllOwnedBy(ownerUserId);
    }

    /**
     * Validates a pasted token against GitHub (its login, its scopes) before storing
     * it — refusing one that cannot be verified, or that lacks {@value
     * #REQUIRED_TOKEN_SCOPE} (#550's own done-when: "rejects one without repo with a
     * clear 400").
     */
    public AddResult addByToken(long ownerUserId, String token) {
        if (token == null || token.isBlank()) {
            return new AddResult.Invalid("a token is required");
        }
        String trimmed = token.strip();
        Optional<GhTokenIntrospector.Introspection> introspection = introspector.introspect(trimmed);
        if (introspection.isEmpty()) {
            return new AddResult.Invalid("could not verify this token with GitHub");
        }
        if (!introspection.get().scopes().contains(REQUIRED_TOKEN_SCOPE)) {
            return new AddResult.Invalid("this token does not have the `" + REQUIRED_TOKEN_SCOPE + "` scope");
        }
        GhAccount account = accountRepository.insert(ownerUserId, introspection.get().login(),
                tokenCipher.encrypt(trimmed), introspection.get().scopes(), Instant.now());
        log.info("Added GitHub account {} ({}) for user {} by pasted token", account.id(), account.login(), ownerUserId);
        return new AddResult.Added(account);
    }

    /**
     * Starts a device flow for {@code ownerUserId} and returns immediately with the
     * code to show them; the actual approval wait happens on {@link #pollExecutor}.
     * {@link DeviceFlowStartResult.NotConfigured} when no OAuth App client id is set
     * ({@code locklane.github.oauth-client-id}) — device flow is simply unavailable
     * until the human registers one (#550's own issue text).
     */
    public DeviceFlowStartResult startDeviceFlow(long ownerUserId) {
        pruneStaleFlows();
        if (oauthClientId.isEmpty()) {
            return new DeviceFlowStartResult.NotConfigured();
        }
        GhDeviceFlow.DeviceCode code;
        try {
            code = deviceFlow.start(oauthClientId.get(), DEVICE_FLOW_SCOPE);
        } catch (RuntimeException e) {
            log.warn("Could not start a GitHub device flow for user {}", ownerUserId, e);
            return new DeviceFlowStartResult.Failed("could not reach GitHub to start sign-in");
        }
        String flowId = UUID.randomUUID().toString();
        DeviceFlowState state = new DeviceFlowState(ownerUserId, Instant.now().plusSeconds(code.expiresInSeconds()));
        flows.put(flowId, state);
        pollExecutor.execute(() -> pollUntilDone(flowId, state, code.deviceCode(), code.intervalSeconds()));
        return new DeviceFlowStartResult.Started(flowId, code.userCode(), code.verificationUri(),
                code.expiresInSeconds());
    }

    /** Owner-scoped: a flow id that exists but belongs to someone else answers empty, same as an unknown one. */
    public Optional<DeviceFlowStatus> statusOf(long ownerUserId, String flowId) {
        DeviceFlowState state = flows.get(flowId);
        if (state == null || state.ownerUserId != ownerUserId) {
            return Optional.empty();
        }
        return Optional.of(state.status());
    }

    /** 409-shaped refusal (#550) when {@code accountId} is still referenced by a project; owner-scoped like everything else. */
    public RemoveResult remove(long ownerUserId, long accountId) {
        Optional<GhAccount> account = accountRepository.findById(accountId).filter(a -> a.ownerUserId() == ownerUserId);
        if (account.isEmpty()) {
            return new RemoveResult.NotFound();
        }
        List<String> referencingProjects = projectRepository.findNamesReferencingGithubAccount(accountId);
        if (!referencingProjects.isEmpty()) {
            return new RemoveResult.InUse(referencingProjects);
        }
        accountRepository.delete(accountId);
        log.info("Removed GitHub account {} ({}) for user {}", accountId, account.get().login(), ownerUserId);
        return new RemoveResult.Removed();
    }

    /** Off {@code pollExecutor}: polls GitHub every {@code intervalSeconds} (widening on slow_down) until it settles. */
    private void pollUntilDone(String flowId, DeviceFlowState state, String deviceCode, int intervalSeconds) {
        int interval = Math.max(1, intervalSeconds);
        try {
            while (Instant.now().isBefore(state.expiresAt)) {
                Thread.sleep(interval * 1000L);
                GhDeviceFlow.PollResult result = deviceFlow.poll(oauthClientId.orElseThrow(), deviceCode);
                if (result instanceof GhDeviceFlow.PollResult.Success success) {
                    completeFlow(state, success.accessToken());
                    return;
                } else if (result instanceof GhDeviceFlow.PollResult.SlowDown) {
                    interval += 5;
                } else if (result instanceof GhDeviceFlow.PollResult.Expired) {
                    state.fail("the code expired before it was approved");
                    return;
                } else if (result instanceof GhDeviceFlow.PollResult.Denied) {
                    state.fail("sign-in was declined");
                    return;
                } else if (result instanceof GhDeviceFlow.PollResult.Error error) {
                    log.warn("Device-flow poll for {} failed: {}", flowId, error.message());
                    state.fail(error.message());
                    return;
                }
                // Pending: loop and poll again.
            }
            state.fail("the code expired before it was approved");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while polling device flow {}", flowId, e);
        } catch (RuntimeException e) {
            log.error("Device-flow poll for {} failed unexpectedly", flowId, e);
            state.fail("an unexpected error occurred");
        }
    }

    private void completeFlow(DeviceFlowState state, String accessToken) {
        Optional<GhTokenIntrospector.Introspection> introspection = introspector.introspect(accessToken);
        if (introspection.isEmpty()) {
            log.warn("Device flow for user {} produced a token GitHub would not introspect", state.ownerUserId);
            state.fail("signed in, but could not read the account back from GitHub");
            return;
        }
        GhAccount account = accountRepository.insert(state.ownerUserId, introspection.get().login(),
                tokenCipher.encrypt(accessToken), introspection.get().scopes(), Instant.now());
        log.info("Added GitHub account {} ({}) for user {} by device flow", account.id(), account.login(),
                state.ownerUserId);
        state.complete(account);
    }

    private void pruneStaleFlows() {
        Instant cutoff = Instant.now().minus(FLOW_RETENTION_AFTER_FINISH);
        flows.entrySet().removeIf(entry -> entry.getValue().finishedBefore(cutoff));
    }

    public sealed interface AddResult permits AddResult.Added, AddResult.Invalid {
        record Added(GhAccount account) implements AddResult {
        }

        record Invalid(String message) implements AddResult {
        }
    }

    public sealed interface DeviceFlowStartResult permits DeviceFlowStartResult.Started,
            DeviceFlowStartResult.NotConfigured, DeviceFlowStartResult.Failed {
        record Started(String flowId, String userCode, String verificationUri, int expiresInSeconds)
                implements DeviceFlowStartResult {
        }

        record NotConfigured() implements DeviceFlowStartResult {
        }

        record Failed(String message) implements DeviceFlowStartResult {
        }
    }

    public sealed interface RemoveResult permits RemoveResult.Removed, RemoveResult.NotFound, RemoveResult.InUse {
        record Removed() implements RemoveResult {
        }

        record NotFound() implements RemoveResult {
        }

        record InUse(List<String> projectNames) implements RemoveResult {
        }
    }

    public record DeviceFlowStatus(Status status, GhAccount account, String errorMessage) {
        public enum Status { PENDING, COMPLETE, FAILED }
    }

    /** Mutable only from {@link #pollUntilDone}'s single writer thread per flow; readers see updates via volatile. */
    private static final class DeviceFlowState {
        final long ownerUserId;
        final Instant expiresAt;
        private volatile DeviceFlowStatus.Status status = DeviceFlowStatus.Status.PENDING;
        private volatile GhAccount account;
        private volatile String errorMessage;
        private volatile Instant finishedAt;

        DeviceFlowState(long ownerUserId, Instant expiresAt) {
            this.ownerUserId = ownerUserId;
            this.expiresAt = expiresAt;
        }

        void complete(GhAccount account) {
            this.account = account;
            this.status = DeviceFlowStatus.Status.COMPLETE;
            this.finishedAt = Instant.now();
        }

        void fail(String message) {
            this.errorMessage = message;
            this.status = DeviceFlowStatus.Status.FAILED;
            this.finishedAt = Instant.now();
        }

        DeviceFlowStatus status() {
            return new DeviceFlowStatus(status, account, errorMessage);
        }

        boolean finishedBefore(Instant cutoff) {
            return finishedAt != null && finishedAt.isBefore(cutoff);
        }
    }
}
