package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeTokenSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTheAccessTokenFromTheCredentialsFile() throws IOException {
        Path file = tempDir.resolve(".credentials.json");
        Files.writeString(file, "{\"claudeAiOauth\": {\"accessToken\": \"file-token\"}}");
        ClaudeTokenSource source = new ClaudeTokenSource(file, service -> Optional.empty());

        assertThat(source.accessToken()).contains("file-token");
    }

    @Test
    void fallsBackToTheKeychainWhenTheFileIsMissing() {
        ClaudeTokenSource source = new ClaudeTokenSource(
                tempDir.resolve("missing.json"),
                service -> service.equals("Claude Code-credentials")
                        ? Optional.of("{\"claudeAiOauth\": {\"accessToken\": \"keychain-token\"}}")
                        : Optional.empty());

        assertThat(source.accessToken()).contains("keychain-token");
    }

    @Test
    void emptyWhenNeitherSourceHasAToken() {
        ClaudeTokenSource source = new ClaudeTokenSource(tempDir.resolve("missing.json"), service -> Optional.empty());

        assertThat(source.accessToken()).isEmpty();
    }

    @Test
    void emptyWhenTheFileIsNotValidJson() throws IOException {
        Path file = tempDir.resolve(".credentials.json");
        Files.writeString(file, "not json");
        ClaudeTokenSource source = new ClaudeTokenSource(file, service -> Optional.empty());

        assertThat(source.accessToken()).isEmpty();
    }
}
