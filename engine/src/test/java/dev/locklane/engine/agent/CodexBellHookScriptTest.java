package dev.locklane.engine.agent;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #856's done-when: {@code <data-dir>/hooks/bell.sh} exists and is executable
 * once this bean is constructed (what happens at engine startup), running it rings a
 * bell on its own controlling terminal, and constructing it again — the shape of a
 * second engine start against the same data directory — is safe and leaves the same
 * script in place.
 */
class CodexBellHookScriptTest {

    @Test
    void installsAnExecutableScriptUnderHooksBellSh(@TempDir Path dataDir) throws Exception {
        CodexBellHookScript installed = new CodexBellHookScript(dataDir.toString());

        Path expected = dataDir.resolve("hooks").resolve("bell.sh");
        assertThat(installed.scriptPath()).isEqualTo(expected);
        assertThat(expected).exists().isExecutable();
    }

    @Test
    void runningTheScriptRingsABellOnItsOwnControllingTerminal(@TempDir Path dataDir) throws Exception {
        CodexBellHookScript installed = new CodexBellHookScript(dataDir.toString());

        // A real pty (pty4j, the same mechanism PtySession itself uses), not a plain
        // subprocess: `/dev/tty` inside the script resolves to whatever the calling
        // process's controlling terminal is, and only a real pty gives it one to
        // write a bell onto and for this test to then read back.
        PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[] {"/bin/sh", installed.scriptPath().toString(), "ignored-json-payload"})
                .start();
        try {
            assertThat(readUntilBellOrTimeout(process.getInputStream(), Duration.ofSeconds(5)))
                    .as("the script's own output contained a bare BEL byte")
                    .isTrue();
        } finally {
            process.destroy();
        }
    }

    @Test
    void constructingItAgainAgainstTheSameDataDirIsSafeAndLeavesTheScriptInPlace(@TempDir Path dataDir) throws Exception {
        new CodexBellHookScript(dataDir.toString());
        Path scriptPath = dataDir.resolve("hooks").resolve("bell.sh");
        String firstContent = Files.readString(scriptPath, StandardCharsets.UTF_8);

        CodexBellHookScript second = new CodexBellHookScript(dataDir.toString());

        assertThat(second.scriptPath()).isEqualTo(scriptPath);
        assertThat(scriptPath).exists().isExecutable();
        assertThat(Files.readString(scriptPath, StandardCharsets.UTF_8)).isEqualTo(firstContent);
    }

    @Test
    void aStaleOrTamperedScriptIsOverwrittenRatherThanLeftAlone(@TempDir Path dataDir) throws Exception {
        Path scriptPath = dataDir.resolve("hooks").resolve("bell.sh");
        Files.createDirectories(scriptPath.getParent());
        Files.writeString(scriptPath, "#!/bin/sh\necho stale\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(scriptPath, PosixFilePermissions.fromString("rw-------"));

        new CodexBellHookScript(dataDir.toString());

        assertThat(Files.readString(scriptPath, StandardCharsets.UTF_8)).isEqualTo(CodexBellHookScript.CONTENT);
        assertThat(scriptPath).isExecutable();
    }

    /**
     * True the moment a BEL (0x07) is read, false if the timeout passes with none.
     * A blocking read on a background thread, not an {@code available()} poll: a
     * pty's {@link InputStream} does not reliably report bytes as available before
     * they are read (the same reason {@code PtySession}'s own drain loop blocks on
     * {@code read} rather than polling).
     */
    private static boolean readUntilBellOrTimeout(InputStream in, Duration timeout) throws Exception {
        CompletableFuture<Boolean> sawBel = CompletableFuture.supplyAsync(() -> {
            byte[] buffer = new byte[256];
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    for (int i = 0; i < n; i++) {
                        if (buffer[i] == 0x07) {
                            return true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // silent: the process ending closes this stream; nothing more to read.
            }
            return false;
        });
        try {
            return sawBel.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false;
        }
    }
}
