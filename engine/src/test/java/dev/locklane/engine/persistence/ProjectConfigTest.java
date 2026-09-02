package dev.locklane.engine.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code projectCloneExecutor}'s tasks run on their own virtual thread, off the
 * caller — an uncaught exception there would otherwise vanish into the JVM's default
 * handler (stderr), never the log (#546). Asserts the wrapping bean actually logs it
 * at ERROR.
 */
class ProjectConfigTest {

    @Test
    void anUncaughtExceptionInATaskIsLoggedAtError() throws InterruptedException {
        Executor executor = new ProjectConfig().projectCloneExecutor();
        CountDownLatch logged = new CountDownLatch(1);

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                super.append(event);
                logged.countDown();
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            executor.execute(() -> {
                throw new RuntimeException("boom");
            });
            assertThat(logged.await(5, TimeUnit.SECONDS)).as("the task's failure was logged").isTrue();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("boom");
        });
    }
}
