package dev.locklane.engine.usage;

import java.time.Instant;

/** The whole sidebar widget's payload: both providers, as of one fetch. */
public record UsageSnapshot(ProviderUsage claude, ProviderUsage codex, Instant updatedAt) {
}
