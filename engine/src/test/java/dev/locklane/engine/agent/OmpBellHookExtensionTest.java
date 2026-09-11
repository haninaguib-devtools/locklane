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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #857's done-when: {@code <data-dir>/hooks/omp-bell.js} exists once this bean
 * is constructed (what happens at engine startup), its exported factory registers
 * exactly the three OMP extension events named in the task record, each ringing a
 * bell on its own controlling terminal under the right condition, and constructing it
 * again — the shape of a second engine start against the same data directory — is
 * safe and leaves the same extension in place.
 *
 * <p>OMP itself is not installed in CI, so "running it" here means what this repo can
 * actually prove without a real OMP: the file's exported factory, invoked with a fake
 * {@code api.on} the same shape OMP's own {@code ExtensionAPI.on(event, handler)} is
 * (confirmed against the installed OMP binary, see the task record), registers the
 * right handlers, and each handler — run under a real pty, the same mechanism
 * {@code PtySession} itself uses — writes a bare bell to its own controlling terminal
 * under the documented condition and stays silent otherwise. Requires {@code node} on
 * PATH (already a build requirement for the Angular client).
 */
class OmpBellHookExtensionTest {

    @Test
    void installsTheExtensionUnderHooksOmpBellJs(@TempDir Path dataDir) throws Exception {
        OmpBellHookExtension installed = new OmpBellHookExtension(dataDir.toString());

        Path expected = dataDir.resolve("hooks").resolve("omp-bell.js");
        assertThat(installed.extensionPath()).isEqualTo(expected);
        assertThat(expected).exists();
        assertThat(Files.readString(expected, StandardCharsets.UTF_8)).isEqualTo(OmpBellHookExtension.CONTENT);
    }

    @Test
    void constructingItAgainAgainstTheSameDataDirIsSafeAndLeavesTheExtensionInPlace(@TempDir Path dataDir)
            throws Exception {
        new OmpBellHookExtension(dataDir.toString());
        Path extensionPath = dataDir.resolve("hooks").resolve("omp-bell.js");
        String firstContent = Files.readString(extensionPath, StandardCharsets.UTF_8);

        OmpBellHookExtension second = new OmpBellHookExtension(dataDir.toString());

        assertThat(second.extensionPath()).isEqualTo(extensionPath);
        assertThat(Files.readString(extensionPath, StandardCharsets.UTF_8)).isEqualTo(firstContent);
    }

    @Test
    void aStaleExtensionIsOverwrittenRatherThanLeftAlone(@TempDir Path dataDir) throws Exception {
        Path extensionPath = dataDir.resolve("hooks").resolve("omp-bell.js");
        Files.createDirectories(extensionPath.getParent());
        Files.writeString(extensionPath, "module.exports = function () {};\n", StandardCharsets.UTF_8);

        new OmpBellHookExtension(dataDir.toString());

        assertThat(Files.readString(extensionPath, StandardCharsets.UTF_8)).isEqualTo(OmpBellHookExtension.CONTENT);
    }

    @Test
    void anEndedTurnRingsTheBellOnItsOwnControllingTerminal(@TempDir Path dataDir) throws Exception {
        assertThat(ringsBellFor(dataDir, "agent_end", "{willContinue: false}")).isTrue();
    }

    @Test
    void aTurnThatWillContinueDoesNotRing(@TempDir Path dataDir) throws Exception {
        assertThat(ringsBellFor(dataDir, "agent_end", "{willContinue: true}")).isFalse();
    }

    @Test
    void anApprovalRequestRingsTheBellOnItsOwnControllingTerminal(@TempDir Path dataDir) throws Exception {
        assertThat(ringsBellFor(dataDir, "tool_approval_requested", "{toolName: 'bash'}")).isTrue();
    }

    @Test
    void theBuiltInAskToolStartingRingsTheBellOnItsOwnControllingTerminal(@TempDir Path dataDir) throws Exception {
        assertThat(ringsBellFor(dataDir, "tool_execution_start", "{toolName: 'ask'}")).isTrue();
    }

    @Test
    void anyOtherToolStartingDoesNotRing(@TempDir Path dataDir) throws Exception {
        assertThat(ringsBellFor(dataDir, "tool_execution_start", "{toolName: 'bash'}")).isFalse();
    }

    /**
     * Loads the installed extension under Node (OMP's own extension API is a plain
     * {@code api.on(event, handler)} registry, the same shape confirmed against the
     * installed OMP binary), fires {@code event} with {@code eventJson}, and reports
     * whether a bell appeared on the child process's own controlling terminal within
     * five seconds.
     */
    private static boolean ringsBellFor(Path dataDir, String event, String eventJson) throws Exception {
        OmpBellHookExtension installed = new OmpBellHookExtension(dataDir.toString());
        Path driver = dataDir.resolve("drive.js");
        Files.writeString(driver, """
                const factory = require(%s);
                const handlers = {};
                factory({ on(name, fn) { handlers[name] = fn; } });
                const handler = handlers[%s];
                if (handler) {
                  handler(%s);
                }
                """.formatted(jsStringLiteral(installed.extensionPath().toString()), jsStringLiteral(event), eventJson),
                StandardCharsets.UTF_8);

        PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[] {"node", driver.toString()})
                .start();
        try {
            return readUntilBellOrTimeout(process.getInputStream(), Duration.ofSeconds(5));
        } finally {
            process.destroy();
        }
    }

    private static String jsStringLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * True the moment a BEL (0x07) is read, false if the timeout passes with none. A
     * blocking read on a background thread, not an {@code available()} poll: a pty's
     * {@link InputStream} does not reliably report bytes as available before they are
     * read (the same reason {@code PtySession}'s own drain loop blocks on {@code
     * read} rather than polling) — see {@code CodexBellHookScriptTest}.
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
