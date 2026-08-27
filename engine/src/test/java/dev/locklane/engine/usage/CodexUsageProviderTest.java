package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodexUsageProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void unavailableWhenAuthFileIsMissing() {
        CodexTokenSource tokenSource = new CodexTokenSource(tempDir.resolve("missing.json"));
        CodexUsageProvider provider = new CodexUsageProvider(tokenSource, (url, headers) -> {
            throw new AssertionError("must not call out with no credentials");
        });

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
    }

    @Test
    void unavailableWhenTheUpstreamCallFails() throws IOException {
        CodexTokenSource tokenSource = authFile("a-token", "acct-1");
        CodexUsageProvider provider = new CodexUsageProvider(tokenSource, (url, headers) -> java.util.Optional.empty());

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
    }

    @Test
    void parsesBothWindowsAndSendsTheAccountHeader() throws IOException {
        CodexTokenSource tokenSource = authFile("a-token", "acct-1");
        String body = """
                {"rate_limit": {
                  "primary_window": {"used_percent": 30, "reset_at": 1000},
                  "secondary_window": {"used_percent": 70, "reset_at": 2000}
                }}""";
        CodexUsageProvider provider = new CodexUsageProvider(tokenSource, (url, headers) -> {
            assertThat(headers.get("Authorization")).isEqualTo("Bearer a-token");
            assertThat(headers.get("chatgpt-account-id")).isEqualTo("acct-1");
            return java.util.Optional.of(body);
        });

        ProviderUsage usage = provider.fetch();

        assertThat(usage.available()).isTrue();
        assertThat(usage.fiveHour().percentLeft()).isEqualTo(70.0);
        assertThat(usage.weekly().percentLeft()).isEqualTo(30.0);
    }

    private CodexTokenSource authFile(String token, String accountId) throws IOException {
        Path file = tempDir.resolve("auth-" + token.hashCode() + "-" + accountId.hashCode() + ".json");
        Files.writeString(file, """
                {"tokens": {"access_token": "%s", "account_id": "%s"}}""".formatted(token, accountId));
        return new CodexTokenSource(file);
    }
}
