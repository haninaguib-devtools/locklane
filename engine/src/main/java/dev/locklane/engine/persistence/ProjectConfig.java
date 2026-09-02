package dev.locklane.engine.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ProjectConfig {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfig.class);

    /**
     * One virtual thread per clone (#42) — cloning is I/O-bound and short-lived, never
     * queued behind a fixed pool. Every submitted task is wrapped so an uncaught
     * exception is logged at ERROR (#546) instead of being lost to the JVM's default
     * handler — a virtual-thread-per-task executor has no shared thread whose own
     * {@code UncaughtExceptionHandler} could catch it centrally. This is a backstop:
     * {@link ProjectCheckoutService}'s own tasks already catch and log with the
     * project's identity, so this only ever fires for a defect this class's own catches
     * missed.
     */
    @Bean
    public Executor projectCloneExecutor() {
        Executor delegate = Executors.newVirtualThreadPerTaskExecutor();
        return task -> delegate.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Uncaught exception on projectCloneExecutor", e);
            }
        });
    }
}
