package dev.locklane.engine.usage;

/** The two fields Codex's own backend calls need together: a bearer token and its account. */
public record CodexCredentials(String accessToken, String accountId) {
}
