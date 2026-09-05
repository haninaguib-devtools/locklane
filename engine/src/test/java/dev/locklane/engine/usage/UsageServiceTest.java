package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UsageServiceTest {

    @Test
    void repeatedPollsWithinTheCacheWindowMakeNoUpstreamCall() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CountingProvider claude = new CountingProvider("claude");
        CountingProvider codex = new CountingProvider("codex");
        CountingProvider opencode = new CountingProvider("opencode");
        UsageService service = new UsageService(List.of(claude, codex, opencode), clock);

        service.snapshot();
        service.snapshot();
        service.snapshot();

        assertThat(claude.calls.get()).isEqualTo(1);
        assertThat(codex.calls.get()).isEqualTo(1);
        assertThat(opencode.calls.get()).isEqualTo(1);
    }

    @Test
    void aPollAfterTheCacheExpiresFetchesAgain() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CountingProvider claude = new CountingProvider("claude");
        CountingProvider codex = new CountingProvider("codex");
        CountingProvider opencode = new CountingProvider("opencode");
        UsageService service = new UsageService(List.of(claude, codex, opencode), clock);

        service.snapshot();
        clock.now.set(clock.now.get().plus(UsageService.CACHE_TTL).plus(Duration.ofSeconds(1)));
        service.snapshot();

        assertThat(claude.calls.get()).isEqualTo(2);
        assertThat(codex.calls.get()).isEqualTo(2);
        assertThat(opencode.calls.get()).isEqualTo(2);
    }

    @Test
    void snapshotCarriesEachProvidersIdLabelAndColorAlongsideItsUsage() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        UsageService service = new UsageService(List.of(new CountingProvider("claude")), clock);

        UsageSnapshot.ProviderSnapshot snapshot = service.snapshot().providers().get(0);

        assertThat(snapshot.id()).isEqualTo("claude");
        assertThat(snapshot.label()).isEqualTo("Claude label");
        assertThat(snapshot.color()).isEqualTo("#000");
        assertThat(snapshot.usage()).isEqualTo(ProviderUsage.unavailable());
    }

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static final class CountingProvider implements UsageProvider {
        final AtomicInteger calls = new AtomicInteger();
        private final String id;

        CountingProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return id.substring(0, 1).toUpperCase() + id.substring(1) + " label";
        }

        @Override
        public String color() {
            return "#000";
        }

        @Override
        public ProviderUsage fetch() {
            calls.incrementAndGet();
            return ProviderUsage.unavailable();
        }
    }
}
