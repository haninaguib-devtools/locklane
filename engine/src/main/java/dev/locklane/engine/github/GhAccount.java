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
 * response.
 */
public record GhAccount(long id, long ownerUserId, String login, Set<String> scopes, Instant createdAt) {

    public boolean hasWorkflowScope() {
        return scopes.contains("workflow");
    }
}
