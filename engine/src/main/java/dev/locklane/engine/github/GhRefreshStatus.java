package dev.locklane.engine.github;

import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of a project's most recent GitHub fetch (#619): whether it failed, the
 * failure text when it did, and when the last successful fetch happened ({@code null}
 * until one has). Lives in memory alongside the {@link GhIssueCache} it describes —
 * a refresh that fails keeps serving the cached data, and this is what tells the
 * sidenav that the picture it is looking at may be stale.
 */
public record GhRefreshStatus(boolean failing, String failure, Instant lastSuccessAt) {

    /** The state before any fetch has been attempted. */
    static GhRefreshStatus initial() {
        return new GhRefreshStatus(false, null, null);
    }

    GhRefreshStatus succeeded(Instant at) {
        return new GhRefreshStatus(false, null, at);
    }

    GhRefreshStatus failed(String failure) {
        return new GhRefreshStatus(true, failure, lastSuccessAt);
    }

    /**
     * True when the outcome is the same as {@code other}'s — failing or not, and the
     * same failure text — ignoring {@code lastSuccessAt}, which moves on every
     * successful poll and would otherwise make every 30s tick look like a change.
     */
    boolean sameOutcomeAs(GhRefreshStatus other) {
        return failing == other.failing && Objects.equals(failure, other.failure);
    }
}
