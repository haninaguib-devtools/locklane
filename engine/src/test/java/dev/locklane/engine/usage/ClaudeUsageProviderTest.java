package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeUsageProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void unavailableWhenNoCredentialsFileOrKeychainEntry() {
        ClaudeTokenSource tokenSource = new ClaudeTokenSource(tempDir.resolve("missing.json"), service -> Optional.empty());
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, (url, headers) -> {
            throw new AssertionError("must not call out with no token");
        });

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
    }

    @Test
    void unavailableWhenTheUpstreamCallFails() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.empty()));

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
    }

    @Test
    void unavailableWhenTheResponseBodyDoesNotParse() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.of("not json")));

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
    }

    @Test
    void parsesBothWindowsFromTheDocumentedShape() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"rate_limits": {
                  "five_hour": {"used_percentage": 25, "resets_at": 1000},
                  "seven_day": {"used_percentage": 60, "resets_at": 2000}
                }}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> {
            assertThat(headers.get("Authorization")).isEqualTo("Bearer a-token");
            return Optional.of(body);
        }));

        ProviderUsage usage = provider.fetch();

        assertThat(usage.available()).isTrue();
        assertThat(usage.fiveHour().percentLeft()).isEqualTo(75.0);
        assertThat(usage.weekly().percentLeft()).isEqualTo(40.0);
    }

    @Test
    void aMissingWindowIsNullButTheOtherStillCounts() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"rate_limits": {"five_hour": {"used_percentage": 10, "resets_at": 1000}}}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.of(body)));

        ProviderUsage usage = provider.fetch();

        assertThat(usage.available()).isTrue();
        assertThat(usage.fiveHour()).isNotNull();
        assertThat(usage.weekly()).isNull();
    }

    private ClaudeTokenSource credentialsFile(String token) throws IOException {
        Path file = tempDir.resolve("credentials-" + token.hashCode() + ".json");
        Files.writeString(file, "{\"claudeAiOauth\": {\"accessToken\": \"" + token + "\"}}");
        return new ClaudeTokenSource(file, service -> Optional.empty());
    }

    private static UsageHttpClient stub(UsageHttpClient delegate) {
        return delegate;
    }
}
