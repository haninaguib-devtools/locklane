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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
