package dev.locklane.engine.github;

import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.locklane.engine.ws.EventBroadcaster;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #786's done-when: warming starts as soon as the engine comes up, rather than
 * waiting out {@code ProjectGhResources}'s own {@code REFRESH_INTERVAL_MS} (30 s)
 * the way every later tick does. Boots the real {@code @Scheduled} method on a real
 * {@code TaskScheduler} -- the same {@code ApplicationContextRunner} +
 * {@code TaskSchedulingAutoConfiguration} shape {@code SchedulingPoolSizeTest} already
 * uses -- and asserts the first fetch lands well inside a window far shorter than the
 * interval, which only an {@code initialDelay} of zero can satisfy.
 *
 * <p>The other three done-when properties -- covers every ready project, skips one
 * without a usable checkout, isolates one project's failure from the rest -- are
 * {@code refreshAll}'s own existing behavior, unchanged by this task and already
 * covered by {@code ProjectGhResourcesTest}'s
 * {@code refreshAllPollsAReadyProjectNobodyHasLookedUpYet},
 * {@code refreshAllSkipsProjectsThatAreStillCloningOrFailedToClone}, and
 * {@code refreshAllKeepsPollingTheOtherProjectsWhenOneOfThemBlowsUp}.
 */
class ProjectGhResourcesSchedulingTest {

    @Test
    void refreshAllFetchesAReadyProjectWithinSecondsOfStartupInsteadOfWaitingForTheFirstThirtySecondTick(
            @TempDir Path dataDir) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        repository.createReady("myproj", "url", dataDir.resolve("myproj"), "main", 1L, Instant.now());
        GhAccountRepository ghAccountRepository = TestSqliteDatabases.newGhAccountRepository(dataDir);
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        CountDownLatch fetched = new CountDownLatch(1);
        ProjectGhResources resources = new ProjectGhResources(repository, ghAccountRepository, cipher,
                (path, token) -> new LatchGhClient(fetched));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("spring.task.scheduling.pool.size=4")
                .withBean(ProjectGhResources.class, () -> resources)
                .withUserConfiguration(SchedulingEnabled.class)
                .run(context -> assertThat(fetched.await(5, TimeUnit.SECONDS))
                        .as("refreshAll's first run should fire immediately at startup, long before "
                                + "the 30 s scheduled-refresh interval")
                        .isTrue());
    }

    // #991: the poll backs off while no browser is connected to the events channel.

    @Test
    void withNoClientConnectedTheTickPollsOnlyOncePerIdleInterval(@TempDir Path dataDir) throws IOException {
        MutableClock clock = new MutableClock();
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        when(broadcaster.connectedClientCount()).thenReturn(0);
        ProjectGhResources resources = resources(dataDir, broadcaster, clock, new AtomicInteger(), Runnable::run);

        assertThat(resources.refreshDue()).as("the first tick after startup (#786)").isTrue();
        resources.refreshAll();
        clock.advance(Duration.ofSeconds(30));
        assertThat(resources.refreshDue()).isFalse();
        clock.advance(Duration.ofMinutes(4));
        assertThat(resources.refreshDue()).isFalse();
        clock.advance(Duration.ofSeconds(30));
        assertThat(resources.refreshDue()).isTrue();
    }

    @Test
    void withAClientConnectedEveryTickPolls(@TempDir Path dataDir) throws IOException {
        MutableClock clock = new MutableClock();
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        when(broadcaster.connectedClientCount()).thenReturn(1);
        ProjectGhResources resources = resources(dataDir, broadcaster, clock, new AtomicInteger(), Runnable::run);
        resources.refreshAll();

        clock.advance(Duration.ofSeconds(30));

        assertThat(resources.refreshDue()).isTrue();
    }

    @Test
    void aClientConnectingAfterAnIdleStretchRefreshesAtOnceAndRestoresTheThirtySecondCadence(@TempDir Path dataDir)
            throws IOException {
        MutableClock clock = new MutableClock();
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        when(broadcaster.connectedClientCount()).thenReturn(0);
        AtomicInteger fetches = new AtomicInteger();
        ProjectGhResources resources = resources(dataDir, broadcaster, clock, fetches, Runnable::run);
        resources.scheduledRefresh();
        clock.advance(Duration.ofMinutes(2));
        assertThat(fetches.get()).isEqualTo(1);

        when(broadcaster.connectedClientCount()).thenReturn(1);
        resources.clientConnected();

        assertThat(fetches.get()).as("an immediate refresh on connect").isEqualTo(2);
        clock.advance(Duration.ofSeconds(30));
        resources.scheduledRefresh();
        assertThat(fetches.get()).as("the next 30 s tick polls again").isEqualTo(3);
    }

    @Test
    void aClientConnectingRightAfterARefreshDoesNotRefreshAgain(@TempDir Path dataDir) throws IOException {
        MutableClock clock = new MutableClock();
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        AtomicInteger fetches = new AtomicInteger();
        ProjectGhResources resources = resources(dataDir, broadcaster, clock, fetches, Runnable::run);
        resources.refreshAll();
        clock.advance(Duration.ofSeconds(5));

        resources.clientConnected();

        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void theEngineListensForClientsConnecting(@TempDir Path dataDir) throws IOException {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);

        new ProjectGhResources(repository, TestSqliteDatabases.newGhAccountRepository(dataDir),
                new TokenCipher(new EncryptionKeyProvider(dataDir.toString())), broadcaster, 300_000L);

        verify(broadcaster).onClientConnected(any());
    }

    private static ProjectGhResources resources(Path dataDir, EventBroadcaster broadcaster, Clock clock,
            AtomicInteger fetches, Executor executor) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(dataDir);
        repository.createReady("myproj", "url", dataDir.resolve("myproj"), "main", 1L, Instant.now());
        return new ProjectGhResources(repository, TestSqliteDatabases.newGhAccountRepository(dataDir),
                new TokenCipher(new EncryptionKeyProvider(dataDir.toString())), broadcaster,
                (path, token) -> new CountingGhClient(fetches), clock, Duration.ofMinutes(5), executor);
    }

    /** A clock a test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Counts full refreshes of the one project. */
    private static final class CountingGhClient implements GhClient {
        private final AtomicInteger fetches;

        CountingGhClient(AtomicInteger fetches) {
            this.fetches = fetches;
        }

        @Override
        public List<GhIssue> issues() {
            fetches.incrementAndGet();
            return List.of();
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }

    @Configuration
    @EnableScheduling
    static class SchedulingEnabled {
    }

    /** Counts down as soon as the scheduled warm-up actually fetches this project. */
    private static final class LatchGhClient implements GhClient {
        private final CountDownLatch latch;

        LatchGhClient(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public List<GhIssue> issues() {
            latch.countDown();
            return List.of();
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
