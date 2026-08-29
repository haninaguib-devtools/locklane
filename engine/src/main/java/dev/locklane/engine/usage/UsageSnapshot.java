package dev.locklane.engine.usage;

import java.time.Instant;

/** The whole sidebar widget's payload: all three providers, as of one fetch. */
public record UsageSnapshot(ProviderUsage claude, ProviderUsage codex, ProviderUsage opencode, Instant updatedAt) {
}
