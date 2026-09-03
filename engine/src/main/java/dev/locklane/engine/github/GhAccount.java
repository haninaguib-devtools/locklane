package dev.locklane.engine.github;

import java.time.Instant;
import java.util.Set;

/**
 * A GitHub account Locklane owns (#550) — signed in through the accounts page's
 * device flow or a pasted token, never read off the engine host's own {@code gh}
 * login. {@code ownerUserId} (ADR-105) is the Locklane user who added it; only they
 * can see or choose it. {@code scopes} are the classic OAuth scopes GitHub reported
 * for the token at the moment it was added — never re-checked later — so the picker
 * can warn before a t-workflow bootstrap (#531) without another round trip. The
 * token itself never appears here: it is a separate, engine-internal lookup
 * ({@code GhAccountRepository#findEncryptedToken}), never returned from an API
 * response — and neither does the refresh token (#656,
 * {@code GhAccountRepository#findEncryptedRefreshToken}).
 *
 * <p>The three trailing fields describe a short-lived device-flow token's lifetime
 * (#656): when the access token dies, when the refresh token that renews it dies,
 * and when a renewal last failed for good. All {@code null} for a pasted-token
 * account, which does not expire and is never renewed.
 */
public record GhAccount(long id, long ownerUserId, String login, Set<String> scopes, Instant createdAt,
        Instant tokenExpiresAt, Instant refreshTokenExpiresAt, Instant renewalFailedAt) {

    /** A non-expiring account — the only shape that existed before #656. */
    public GhAccount(long id, long ownerUserId, String login, Set<String> scopes, Instant createdAt) {
        this(id, ownerUserId, login, scopes, createdAt, null, null, null);
    }

    public boolean hasWorkflowScope() {
        return scopes.contains("workflow");
    }

    /**
     * True when this account can no longer be kept working on its own (#656): a
     * renewal failed and was not retried, or its refresh token has itself expired
     * (GitHub's six-month idle limit). The only way back is to remove the account
     * and sign in again; the accounts page says so.
     */
    public boolean needsReconnect(Instant now) {
        return renewalFailedAt != null
                || (refreshTokenExpiresAt != null && !now.isBefore(refreshTokenExpiresAt));
    }
}
