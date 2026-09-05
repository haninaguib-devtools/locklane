package dev.locklane.engine.usage;

import java.time.Instant;
import java.util.List;

/** The whole sidebar widget's payload: every registered provider's usage, as of one fetch. */
public record UsageSnapshot(List<ProviderSnapshot> providers, Instant updatedAt) {

    /** One provider's identity (id, label, bar color) alongside its fetched usage. */
    public record ProviderSnapshot(String id, String label, String color, ProviderUsage usage) {
    }
}
