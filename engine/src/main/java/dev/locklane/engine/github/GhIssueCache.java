package dev.locklane.engine.github;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps the last successfully fetched issue list in memory and refreshes it on a
 * timer, so a request never has to wait on a live {@code gh} call once the cache is
 * warm (#4's done-when). A refresh failure keeps serving the last good data rather
 * than clearing it — a transient gh/network hiccup should not make the sidenav empty.
 */
@Service
public class GhIssueCache {

    private static final long REFRESH_INTERVAL_MS = 30_000;

    private final GhClient ghClient;
    private final AtomicReference<List<GhIssue>> cached = new AtomicReference<>();

    public GhIssueCache(GhClient ghClient) {
        this.ghClient = ghClient;
    }

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = REFRESH_INTERVAL_MS)
    void refresh() {
        try {
            cached.set(ghClient.issues());
        } catch (GhClient.GhUnavailableException e) {
            // Keep serving whatever is already cached; the next scheduled attempt
            // may succeed. A cache that was never populated stays null here, and
            // issues()/issue() fall back to a live fetch below.
        }
    }

    /** All issues. Serves the cache when warm; falls back to a live fetch when cold. */
    public List<GhIssue> issues() {
        List<GhIssue> snapshot = cached.get();
        if (snapshot != null) {
            return snapshot;
        }
        List<GhIssue> fresh = ghClient.issues();
        cached.set(fresh);
        return fresh;
    }

    public Optional<GhIssue> issue(int number) {
        return issues().stream().filter(i -> i.number() == number).findFirst();
    }
}
