package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeUsageProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void unavailableWhenAuthFileIsMissing() {
        OpenCodeTokenSource tokenSource = new OpenCodeTokenSource(tempDir.resolve("missing.json"));
        OpenCodeUsageProvider provider = new OpenCodeUsageProvider(tokenSource);

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
    }

    @Test
    void unavailableEvenWithValidCredentials() throws IOException {
        Path authFile = tempDir.resolve("auth.json");
        Files.writeString(authFile, """
                {"opencode": {"type": "oauth", "access": "a-token"}}""");
        OpenCodeUsageProvider provider = new OpenCodeUsageProvider(new OpenCodeTokenSource(authFile));

        assertThat(provider.fetch()).isEqualTo(ProviderUsage.unavailable());
    }
}
