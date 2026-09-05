package dev.locklane.engine.usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Caches the last fetched snapshot for a few minutes (#137's Goal) so the sidebar
 * widget can poll on a short timer without hitting any CLI's undocumented endpoint
 * on every poll. A cache miss re-fetches every provider regardless of which one is
 * stale — they are cheap, independent, best-effort calls, not worth tracking
 * separately. One entry per registered {@link UsageProvider} (#695) — adding a
 * provider is a server-only change, the client renders whatever this list contains.
 */
public class UsageService {

    static final Duration CACHE_TTL = Duration.ofMinutes(3);

    private final List<UsageProvider> providers;
    private final Clock clock;

    private UsageSnapshot cached;
    private Instant cacheExpiresAt = Instant.MIN;

    public UsageService(List<UsageProvider> providers, Clock clock) {
        this.providers = providers;
        this.clock = clock;
    }

    public synchronized UsageSnapshot snapshot() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cacheExpiresAt)) {
            return cached;
        }
        UsageSnapshot fresh = new UsageSnapshot(providers.stream()
                .map(provider -> new UsageSnapshot.ProviderSnapshot(provider.id(), provider.label(), provider.color(),
                        provider.fetch()))
                .toList(), now);
        cached = fresh;
        cacheExpiresAt = now.plus(CACHE_TTL);
        return fresh;
    }
}
