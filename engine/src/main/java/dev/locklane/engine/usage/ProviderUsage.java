package dev.locklane.engine.usage;

/**
 * One provider's (Claude or Codex) usage, or the fact that it could not be read this
 * time (#137's Done-when: absent token, expired token, or a failed upstream call all
 * collapse to the same "unavailable" state — never an exception reaching the client).
 */
public record ProviderUsage(boolean available, WindowUsage fiveHour, WindowUsage weekly) {

    public static ProviderUsage unavailable() {
        return new ProviderUsage(false, null, null);
    }
}
