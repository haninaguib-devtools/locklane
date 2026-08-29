package dev.locklane.engine.usage;

/** The bearer token OpenCode's own backend calls would need, mirroring {@link CodexCredentials}. */
public record OpenCodeCredentials(String accessToken) {
}
