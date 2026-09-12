package dev.locklane.engine.agent;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.unix.UnixPtyProcess;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #904's done-when: the bell hook command -- byte-for-byte the same string
 * carried by {@code TerminalWebSocketHandler}'s own {@code BELL_HOOK_COMMAND} and by
 * {@link CodexBellHookScript#CONTENT}'s own bell line, kept here as a literal rather
 * than a shared production constant since neither class exposes one, the same choice
 * {@code TerminalWebSocketHandlerLaunchCommandTest} already makes for its own copy --
 * writes to the device path named by {@code LOCKLANE_TTY} rather than opening
 * {@code /dev/tty} itself. Exercised in exactly the shape Claude Code's own hooks and
 * Codex's {@code notify} command each run it in: a child fully detached from any
 * controlling terminal via {@code setsid} (verified against Claude Code 2.1.267,
 * 2.1.268 and 2.1.269: the hook's own session id equals its pid, {@code tty} reports
 * "not a tty", and {@code /dev/tty} cannot be opened).
 */
class BellHookCommandDetachedShapeTest {

    private static final String BELL_HOOK_COMMAND = "{ printf '\\a' > \"$LOCKLANE_TTY\"; } 2>/dev/null || true";

    @Test
    void withTheVariableSetToARealPtysSlavePathTheBellArrivesOnItsMasterSide() throws Exception {
        // A real pty (pty4j, the same mechanism PtySession itself uses) held open by
        // a long-lived shell purely so this test can read its master side -- nothing
        // ever writes to it directly except the detached hook process below, by name.
        PtyProcess ptyHolder = new PtyProcessBuilder().setCommand(new String[] {"sh", "-c", "sleep 5"}).start();
        try {
            String slavePath = ((UnixPtyProcess) ptyHolder).getPty().getSlaveName();

            Process hook = detachedHookProcess(slavePath);
            boolean exited = hook.waitFor(5, TimeUnit.SECONDS);

            assertThat(exited).as("hook process exited within timeout").isTrue();
            assertThat(hook.exitValue()).isZero();
            assertThat(readUntilBellOrTimeout(ptyHolder.getInputStream(), Duration.ofSeconds(5)))
                    .as("a bare BEL arrived on the pty's master side")
                    .isTrue();
        } finally {
            ptyHolder.destroy();
        }
    }

    @Test
    void withTheVariableUnsetTheHookIsSilentAndExitsZero() throws Exception {
        Process hook = detachedHookProcess(null);
        hook.getOutputStream().close();
        String stdout = new String(hook.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(hook.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean exited = hook.waitFor(5, TimeUnit.SECONDS);

        assertThat(exited).as("hook process exited within timeout").isTrue();
        assertThat(hook.exitValue()).isZero();
        assertThat(stdout).isEmpty();
        assertThat(stderr).isEmpty();
    }

    /**
     * Runs {@link #BELL_HOOK_COMMAND} in a child {@code setsid} detaches into a brand
     * new session with no controlling terminal at all -- the shape Claude Code's own
     * hook mechanism and Codex's {@code notify} command each run their bell command
     * in. {@code slavePath}, when given, is set as {@code LOCKLANE_TTY} in that
     * child's environment; left entirely absent otherwise.
     */
    private static Process detachedHookProcess(String slavePath) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("setsid", "sh", "-c", BELL_HOOK_COMMAND);
        if (slavePath != null) {
            builder.environment().put("LOCKLANE_TTY", slavePath);
        }
        return builder.start();
    }

    /**
     * True the moment a BEL (0x07) is read, false if the timeout passes with none. A
     * blocking read on a background thread, not an {@code available()} poll: a pty's
     * {@link InputStream} does not reliably report bytes as available before they are
     * read (the same reason {@code PtySession}'s own drain loop blocks on {@code
     * read} rather than polling).
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
