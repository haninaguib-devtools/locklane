package dev.locklane.engine.github;

import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a device-flow GitHub account's short-lived token alive (#656,
 * {@code docs/architecture/github-token-lifetime.md}). Two paths, one lock per
 * account: a scheduled pass renews every account whose access token expires within
 * {@link #RENEWAL_MARGIN}, and {@link ProjectGhResources} calls back here the moment
 * a project's {@code gh} fails with {@code Bad credentials} so the account is renewed
 * once, immediately, and the fetch retried. GitHub rotates the refresh token on every
 * use, so the lock is what stops two renewals racing with the same refresh token and
 * stranding the account.
 *
 * <p>After a successful renewal every project referencing the account has its cached
 * {@link ProjectGhContext} evicted, so the next lookup rebuilds the {@code gh} client
 * with the new token — no restart. A renewal GitHub refuses, or a retry that still
 * fails after a renewal, marks the account ({@code renewal_failed_at}) so it is never
 * retried and the accounts page shows it as needing reconnection. A pasted-token
 * account has no refresh token and is never touched by any of this.
 *
 * <p>Nothing here logs a token value — only account ids, logins and lifetimes.
 */
@Service
public class GhTokenRenewalService implements ProjectGhResources.CredentialRenewer {

    private static final Logger log = LoggerFactory.getLogger(GhTokenRenewalService.class);

    /** Renew this far ahead of {@code token_expires_at} — well inside any lifetime GitHub sends (3600 s observed, 28800 s documented). */
    static final Duration RENEWAL_MARGIN = Duration.ofMinutes(5);

    private final GhAccountRepository accountRepository;
    private final ProjectRepository projectRepository;
    private final TokenCipher tokenCipher;
    private final GhDeviceFlow deviceFlow;
    private final ProjectGhResources resources;
    private final Optional<String> oauthClientId;
    private final Clock clock;
    private final Map<Long, Object> accountLocks = new ConcurrentHashMap<>();

    @Autowired
    public GhTokenRenewalService(GhAccountRepository accountRepository, ProjectRepository projectRepository,
            TokenCipher tokenCipher, ProjectGhResources resources,
            @Value("${locklane.github.oauth-client-id:}") String oauthClientId) {
        this(accountRepository, projectRepository, tokenCipher, new HttpGhDeviceFlow(), resources, oauthClientId,
                Clock.systemUTC());
    }

    /** Test-only: a fake {@link GhDeviceFlow} and a controllable clock, so no real GitHub endpoint is ever reached. */
    GhTokenRenewalService(GhAccountRepository accountRepository, ProjectRepository projectRepository,
            TokenCipher tokenCipher, GhDeviceFlow deviceFlow, ProjectGhResources resources, String oauthClientId,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.projectRepository = projectRepository;
        this.tokenCipher = tokenCipher;
        this.deviceFlow = deviceFlow;
        this.resources = resources;
        this.oauthClientId = (oauthClientId == null || oauthClientId.isBlank())
                ? Optional.empty() : Optional.of(oauthClientId.strip());
        this.clock = clock;
        resources.onBadCredentials(this);
    }

    /** The scheduled pass: every account inside the margin is renewed, each under its own lock. */
    @Scheduled(fixedDelayString = "${locklane.github.token-renewal.interval-ms:60000}",
            initialDelayString = "${locklane.github.token-renewal.interval-ms:60000}")
    void renewDue() {
        List<GhAccount> due = accountRepository.findDueForRenewal(clock.instant().plus(RENEWAL_MARGIN));
        for (GhAccount account : due) {
            try {
                renew(account.id());
            } catch (RuntimeException e) {
                log.error("Renewing GitHub account {} ({}) failed unexpectedly", account.id(), account.login(), e);
            }
        }
    }

    /**
     * The on-401 path (#656): renews the account behind {@code projectId} once, right
     * now. {@code false} — no retry is worth making — when the project has no account,
     * the account has no refresh token (pasted), or a renewal already failed for good.
     */
    @Override
    public boolean renewForProject(long projectId) {
        Optional<Long> accountId = projectRepository.findGithubAccountId(projectId);
        if (accountId.isEmpty()) {
            return false;
        }
        return renew(accountId.get());
    }

    /** The retry after a renewal still answered {@code Bad credentials}: the account is done until a human reconnects it. */
    @Override
    public void renewalDidNotHelp(long projectId) {
        projectRepository.findGithubAccountId(projectId).ifPresent(accountId -> {
            markFailed(accountId, "its freshly renewed token was still refused");
        });
    }

    /**
     * One renewal of {@code accountId} under its lock. {@code true} when a new pair was
     * stored and the account's projects evicted. Re-reads the account inside the lock:
     * a renewal that just finished on another thread leaves nothing to do here.
     */
    boolean renew(long accountId) {
        synchronized (accountLocks.computeIfAbsent(accountId, id -> new Object())) {
            Optional<GhAccount> account = accountRepository.findById(accountId);
            if (account.isEmpty() || account.get().renewalFailedAt() != null) {
                return false;
            }
            Optional<String> encryptedRefreshToken = accountRepository.findEncryptedRefreshToken(accountId);
            if (encryptedRefreshToken.isEmpty()) {
                return false;
            }
            Instant now = clock.instant();
            if (account.get().needsReconnect(now)) {
                markFailed(accountId, "its refresh token has expired");
                return false;
            }
            if (oauthClientId.isEmpty()) {
                markFailed(accountId, "no OAuth App client id is configured (locklane.github.oauth-client-id)");
                return false;
            }
            GhDeviceFlow.PollResult result = deviceFlow.refresh(oauthClientId.get(),
                    tokenCipher.decrypt(encryptedRefreshToken.get()));
            if (!(result instanceof GhDeviceFlow.PollResult.Success success)) {
                String reason = result instanceof GhDeviceFlow.PollResult.Error error ? error.message()
                        : result.getClass().getSimpleName();
                markFailed(accountId, "GitHub refused the refresh: " + reason);
                return false;
            }
            store(account.get(), success, now);
            evictProjectsOf(accountId);
            return true;
        }
    }

    private void store(GhAccount account, GhDeviceFlow.PollResult.Success success, Instant now) {
        Instant tokenExpiresAt = success.expiresInSeconds() == null ? null
                : now.plusSeconds(success.expiresInSeconds());
        Instant refreshExpiresAt = success.refreshTokenExpiresInSeconds() == null ? account.refreshTokenExpiresAt()
                : now.plusSeconds(success.refreshTokenExpiresInSeconds());
        // GitHub always rotates the refresh token; keep the old one only if, against
        // its documented behaviour, none came back -- the alternative is an account
        // that can never be renewed again.
        String encryptedRefresh = success.refreshToken() == null
                ? accountRepository.findEncryptedRefreshToken(account.id()).orElse(null)
                : tokenCipher.encrypt(success.refreshToken());
        try {
            accountRepository.updateTokens(account.id(), tokenCipher.encrypt(success.accessToken()), encryptedRefresh,
                    tokenExpiresAt, refreshExpiresAt);
        } catch (RuntimeException e) {
            // The new pair is live on GitHub's side and the old refresh token is
            // dead: this account cannot be renewed again. Say so rather than let the
            // next pass fail with the rotated-away token.
            log.error("Renewed GitHub account {} ({}) but could not store the new token pair; the account needs "
                    + "reconnecting", account.id(), account.login(), e);
            accountRepository.markRenewalFailed(account.id(), now);
            throw e;
        }
        log.info("Renewed GitHub account {} ({}): token expires_in={}s refresh_token={} refresh_token_expires_in={}s",
                account.id(), account.login(), success.expiresInSeconds(),
                success.refreshToken() == null ? "kept" : "rotated", success.refreshTokenExpiresInSeconds());
    }

    private void evictProjectsOf(long accountId) {
        for (long projectId : projectRepository.findIdsReferencingGithubAccount(accountId)) {
            resources.evict(projectId);
        }
    }

    private void markFailed(long accountId, String why) {
        accountRepository.markRenewalFailed(accountId, clock.instant());
        String login = accountRepository.findById(accountId).map(GhAccount::login).orElse("?");
        log.warn("GitHub account {} ({}) needs reconnecting: {} -- remove it on the accounts page and sign in again",
                accountId, login, why);
    }
}
