package dev.locklane.engine.usage;

import java.util.List;

/**
 * One provider's (Claude or Codex) usage, or the fact that it could not be read this
 * time (#137's Done-when: absent token, expired token, or a failed upstream call all
 * collapse to the same "unavailable" state — never an exception reaching the client).
 * {@code modelWeeklyLimits} is a list rather than a named field per model (#288's Goal)
 * so a scoped limit for a model other than today's "Fable" renders without a code change.
 */
public record ProviderUsage(boolean available, WindowUsage fiveHour, WindowUsage weekly,
        List<ModelWeeklyLimit> modelWeeklyLimits) {

    public static ProviderUsage unavailable() {
        return new ProviderUsage(false, null, null, List.of());
    }
}
