package dev.locklane.engine.usage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the usage widget's beans (#137) — plain classes, not {@code @Component}s, so their tests never need Spring. */
@Configuration
public class UsageConfig {

    @Bean
    Clock usageClock() {
        return Clock.systemUTC();
    }

    @Bean
    UsageHttpClient usageHttpClient() {
        return new JdkUsageHttpClient();
    }

    @Bean
    UsageProvider claudeUsageProvider(UsageHttpClient usageHttpClient) {
        return new ClaudeUsageProvider(ClaudeTokenSource.forCurrentUser(), usageHttpClient);
    }

    @Bean
    UsageProvider codexUsageProvider(UsageHttpClient usageHttpClient) {
        return new CodexUsageProvider(CodexTokenSource.forCurrentUser(), usageHttpClient);
    }

    @Bean
    UsageProvider openCodeUsageProvider() {
        return new OpenCodeUsageProvider(OpenCodeTokenSource.forCurrentUser());
    }

    @Bean
    UsageService usageService(
            @Qualifier("claudeUsageProvider") UsageProvider claudeUsageProvider,
            @Qualifier("codexUsageProvider") UsageProvider codexUsageProvider,
            @Qualifier("openCodeUsageProvider") UsageProvider openCodeUsageProvider,
            Clock usageClock) {
        return new UsageService(claudeUsageProvider, codexUsageProvider, openCodeUsageProvider, usageClock);
    }
}
