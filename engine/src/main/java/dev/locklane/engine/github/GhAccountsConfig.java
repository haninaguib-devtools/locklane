package dev.locklane.engine.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class GhAccountsConfig {

    private static final Logger log = LoggerFactory.getLogger(GhAccountsConfig.class);

    /**
     * One virtual thread per in-progress device flow (#550) — each just sleeps and
     * polls GitHub every few seconds until it settles, off the request thread that
     * started it, same shape as {@code ProjectConfig#projectCloneExecutor} (#42):
     * wrapped so an uncaught exception is logged at ERROR (#546) instead of lost.
     */
    @Bean
    public Executor githubDeviceFlowExecutor() {
        Executor delegate = Executors.newVirtualThreadPerTaskExecutor();
        return task -> delegate.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Uncaught exception on githubDeviceFlowExecutor", e);
            }
        });
    }
}
