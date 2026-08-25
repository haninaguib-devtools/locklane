package dev.locklane.engine.github;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitHubConfig {

    @Bean
    public GhClient ghClient() {
        return new CliGhClient();
    }
}
