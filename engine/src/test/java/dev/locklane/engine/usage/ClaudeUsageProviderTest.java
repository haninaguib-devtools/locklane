package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
    void parsesBothWindowsFromTheVerifiedShape() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"five_hour": {"utilization": 25, "resets_at": "2026-01-01T00:00:00Z"},
                 "seven_day": {"utilization": 60, "resets_at": "2026-01-08T00:00:00Z"}}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> {
            assertThat(headers.get("Authorization")).isEqualTo("Bearer a-token");
            return Optional.of(body);
        }));

        ProviderUsage usage = provider.fetch();

        assertThat(usage.available()).isTrue();
        assertThat(usage.fiveHour().percentLeft()).isEqualTo(75.0);
        assertThat(usage.fiveHour().resetsAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(usage.weekly().percentLeft()).isEqualTo(40.0);
    }

    @Test
    void aMissingWindowIsNullButTheOtherStillCounts() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"five_hour": {"utilization": 10, "resets_at": "2026-01-01T00:00:00Z"}}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.of(body)));

        ProviderUsage usage = provider.fetch();

        assertThat(usage.available()).isTrue();
        assertThat(usage.fiveHour()).isNotNull();
        assertThat(usage.weekly()).isNull();
    }

    @Test
    void parsesEveryWeeklyScopedModelLimitFromTheLimitsArray() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"five_hour": {"utilization": 25, "resets_at": "2026-01-01T00:00:00Z"},
                 "seven_day": {"utilization": 35, "resets_at": "2026-01-08T00:00:00Z"},
                 "limits": [
                   {"group": "weekly", "utilization": 43, "resets_at": "2026-01-08T00:00:00Z",
                    "scope": {"model": {"display_name": "Fable"}}},
                   {"group": "weekly", "utilization": 12, "resets_at": "2026-01-09T00:00:00Z",
                    "scope": {"model": {"display_name": "Opus"}}}
                 ]}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.of(body)));

        ProviderUsage usage = provider.fetch();

        assertThat(usage.modelWeeklyLimits()).hasSize(2);
        assertThat(usage.modelWeeklyLimits().get(0).modelName()).isEqualTo("Fable");
        assertThat(usage.modelWeeklyLimits().get(0).window().percentLeft()).isEqualTo(57.0);
        assertThat(usage.modelWeeklyLimits().get(0).window().resetsAt()).isEqualTo(Instant.parse("2026-01-08T00:00:00Z"));
        assertThat(usage.modelWeeklyLimits().get(1).modelName()).isEqualTo("Opus");
        assertThat(usage.modelWeeklyLimits().get(1).window().percentLeft()).isEqualTo(88.0);
    }

    @Test
    void modelWeeklyLimitsIsEmptyWhenTheResponseHasNoLimitsArray() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"five_hour": {"utilization": 25, "resets_at": "2026-01-01T00:00:00Z"}}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.of(body)));

        assertThat(provider.fetch().modelWeeklyLimits()).isEmpty();
    }

    @Test
    void ignoresLimitsEntriesThatAreNotWeeklyOrHaveNoModelScope() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"five_hour": {"utilization": 25, "resets_at": "2026-01-01T00:00:00Z"},
                 "tangelo": null,
                 "nimbus_quill": null,
                 "limits": [
                   {"group": "five_hour", "utilization": 10, "resets_at": "2026-01-01T00:00:00Z",
                    "scope": {"model": {"display_name": "Fable"}}},
                   {"group": "weekly", "utilization": 10, "resets_at": "2026-01-01T00:00:00Z"},
                   {"group": "weekly", "utilization": 10, "resets_at": "2026-01-01T00:00:00Z", "scope": {}},
                   "not-an-object"
                 ]}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.of(body)));

        ProviderUsage usage = provider.fetch();

        assertThat(usage.available()).isTrue();
        assertThat(usage.modelWeeklyLimits()).isEmpty();
    }

    @Test
    void aWindowWithAnUnparsableResetsAtIsTreatedAsAbsent() throws IOException {
        ClaudeTokenSource tokenSource = credentialsFile("a-token");
        String body = """
                {"five_hour": {"utilization": 10, "resets_at": "not-a-date"}}""";
        ClaudeUsageProvider provider = new ClaudeUsageProvider(tokenSource, stub((url, headers) -> Optional.of(body)));

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
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
