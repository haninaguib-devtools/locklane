package dev.locklane.engine.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ProjectConfig {

    /** One virtual thread per clone (#42) — cloning is I/O-bound and short-lived, never queued behind a fixed pool. */
    @Bean
    public Executor projectCloneExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
