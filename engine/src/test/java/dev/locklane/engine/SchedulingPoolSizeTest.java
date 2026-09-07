package dev.locklane.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's scheduled jobs -- the issue poll, both WebSocket heartbeats, the
 * quiescence check, the worktree sweeper, the token renewal, the release check --
 * share one {@code TaskScheduler}, and Spring's default gives it a single thread, on
 * which one slow poll delayed every heartbeat (#763). {@code application.yml} sizes
 * the pool; this proves the value it sets really lets two jobs run at the same time.
 *
 * <p>The value is read from {@code src/main/resources/application.yml} directly,
 * because the test classpath's own {@code application.yml} replaces that file
 * wholesale for every {@code @SpringBootTest} (see its header) -- a context booted the
 * ordinary way here would be testing the test configuration, not the shipped one.
 */
class SchedulingPoolSizeTest {

    private static final String PROPERTY = "spring.task.scheduling.pool.size";

    @Test
    void applicationYmlSizesTheSchedulerPoolForMoreThanOneJobAtATime() throws IOException {
        assertThat(configuredPoolSize()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void twoScheduledJobsRunAtTheSameTimeOnTheConfiguredPool() throws IOException {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues(PROPERTY + "=" + configuredPoolSize())
                .withUserConfiguration(TwoJobsThatMustMeet.class)
                .run(context -> {
                    assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor()
                            .getCorePoolSize()).isEqualTo(configuredPoolSize());
                    TwoJobsThatMustMeet jobs = context.getBean(TwoJobsThatMustMeet.class);
                    assertThat(jobs.bothMet.await(5, TimeUnit.SECONDS))
                            .as("both @Scheduled methods should have been running at the same moment")
                            .isTrue();
                });
    }

    @Test
    void onASingleThreadTheSecondJobNeverStartsWhileTheFirstIsRunning() {
        // The control: proves the test above would fail with Spring's default. Kept
        // short -- the first job is parked at the barrier until the context closes.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues(PROPERTY + "=1")
                .withUserConfiguration(TwoJobsThatMustMeet.class)
                .run(context -> {
                    TwoJobsThatMustMeet jobs = context.getBean(TwoJobsThatMustMeet.class);
                    assertThat(jobs.bothMet.await(1, TimeUnit.SECONDS)).isFalse();
                    jobs.release(); // frees the parked first job so the context closes at once
                });
    }

    /** The main {@code application.yml}'s value, not the test classpath's copy. */
    private static int configuredPoolSize() throws IOException {
        URL main = Collections.list(SchedulingPoolSizeTest.class.getClassLoader().getResources("application.yml"))
                .stream()
                .filter(url -> !url.getPath().contains("test-classes"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("src/main/resources/application.yml is not on the test classpath"));
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new UrlResource(main));
        Properties properties = yaml.getObject();
        String value = properties == null ? null : properties.getProperty(PROPERTY);
        assertThat(value).as(PROPERTY + " in " + main).isNotNull();
        return Integer.parseInt(value);
    }

    /**
     * Two jobs that each run once at startup and wait for the other at a barrier:
     * with one thread the first parks there and the second never gets a turn, so the
     * barrier times out and the latch stays up.
     */
    @Configuration
    @EnableScheduling
    static class TwoJobsThatMustMeet {
        private final CyclicBarrier meet = new CyclicBarrier(2);
        final CountDownLatch bothMet = new CountDownLatch(2);

        @Scheduled(fixedDelay = 60_000)
        void first() {
            meet();
        }

        @Scheduled(fixedDelay = 60_000)
        void second() {
            meet();
        }

        /** Breaks the barrier, so a job still parked at it gives up now rather than at its own timeout. */
        void release() {
            meet.reset();
        }

        private void meet() {
            try {
                meet.await(5, TimeUnit.SECONDS);
                bothMet.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Timed out or broken: this job was alone. The latch stays up.
            }
        }
    }
}
