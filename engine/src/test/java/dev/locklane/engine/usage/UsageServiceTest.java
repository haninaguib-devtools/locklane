package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UsageServiceTest {

    @Test
    void repeatedPollsWithinTheCacheWindowMakeNoUpstreamCall() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CountingProvider claude = new CountingProvider();
        CountingProvider codex = new CountingProvider();
        UsageService service = new UsageService(claude, codex, clock);

        service.snapshot();
        service.snapshot();
        service.snapshot();

        assertThat(claude.calls.get()).isEqualTo(1);
        assertThat(codex.calls.get()).isEqualTo(1);
    }

    @Test
    void aPollAfterTheCacheExpiresFetchesAgain() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        CountingProvider claude = new CountingProvider();
        CountingProvider codex = new CountingProvider();
        UsageService service = new UsageService(claude, codex, clock);

        service.snapshot();
        clock.now.set(clock.now.get().plus(UsageService.CACHE_TTL).plus(Duration.ofSeconds(1)));
        service.snapshot();

        assertThat(claude.calls.get()).isEqualTo(2);
        assertThat(codex.calls.get()).isEqualTo(2);
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

        @Override
        public ProviderUsage fetch() {
            calls.incrementAndGet();
            return ProviderUsage.unavailable();
        }
    }
}
