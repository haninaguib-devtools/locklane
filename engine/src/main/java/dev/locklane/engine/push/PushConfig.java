package dev.locklane.engine.push;

import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.ProjectGhResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Wiring for Web Push (#860): the outbound client, the issue-title lookup, and the executor pushes run on. */
@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    public WebPushClient webPushClient() {
        return new JdkWebPushClient();
    }

    /** The issue's title from the project's own GitHub cache -- a cold cache fetches once, on the push thread. */
    @Bean
    public PushNotifier.IssueTitles issueTitles(ProjectGhResources ghResources) {
        return (projectId, issueNumber) -> ghResources.forProject(projectId)
                .flatMap(context -> context.cache().issue(issueNumber))
                .map(GhIssue::title);
    }

    /**
     * One virtual thread per push, off the session's output-drain thread the bell
     * arrives on -- same shape as {@code GhAccountsConfig#githubDeviceFlowExecutor}:
     * wrapped so an uncaught exception is logged instead of lost.
     */
    @Bean
    public Executor pushExecutor() {
        Executor delegate = Executors.newVirtualThreadPerTaskExecutor();
        return task -> delegate.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Uncaught exception on pushExecutor", e);
            }
        });
    }
}
