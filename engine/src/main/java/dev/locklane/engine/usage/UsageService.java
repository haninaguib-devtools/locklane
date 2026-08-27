package dev.locklane.engine.usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Caches the last fetched snapshot for a few minutes (#137's Goal) so the sidebar
 * widget can poll on a short timer without hitting either CLI's undocumented endpoint
 * on every poll. A cache miss re-fetches both providers regardless of which one is
 * stale — they are cheap, independent, best-effort calls, not worth tracking
 * separately.
 */
public class UsageService {

    static final Duration CACHE_TTL = Duration.ofMinutes(3);

    private final UsageProvider claudeProvider;
    private final UsageProvider codexProvider;
    private final Clock clock;

    private UsageSnapshot cached;
    private Instant cacheExpiresAt = Instant.MIN;

    public UsageService(UsageProvider claudeProvider, UsageProvider codexProvider, Clock clock) {
        this.claudeProvider = claudeProvider;
        this.codexProvider = codexProvider;
        this.clock = clock;
    }

    public synchronized UsageSnapshot snapshot() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cacheExpiresAt)) {
            return cached;
        }
        UsageSnapshot fresh = new UsageSnapshot(claudeProvider.fetch(), codexProvider.fetch(), now);
        cached = fresh;
        cacheExpiresAt = now.plus(CACHE_TTL);
        return fresh;
    }
}
