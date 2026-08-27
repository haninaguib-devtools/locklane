package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodexTokenSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTheAccessTokenAndAccountIdFromTheAuthFile() throws IOException {
        Path file = tempDir.resolve("auth.json");
        Files.writeString(file, "{\"tokens\": {\"access_token\": \"tok\", \"account_id\": \"acct\"}}");
        CodexTokenSource source = new CodexTokenSource(file);

        assertThat(source.credentials()).contains(new CodexCredentials("tok", "acct"));
    }

    @Test
    void emptyWhenTheFileIsMissing() {
        CodexTokenSource source = new CodexTokenSource(tempDir.resolve("missing.json"));

        assertThat(source.credentials()).isEmpty();
    }

    @Test
    void emptyWhenTheAccountIdIsMissing() throws IOException {
        Path file = tempDir.resolve("auth.json");
        Files.writeString(file, "{\"tokens\": {\"access_token\": \"tok\"}}");
        CodexTokenSource source = new CodexTokenSource(file);

        assertThat(source.credentials()).isEmpty();
    }
}
