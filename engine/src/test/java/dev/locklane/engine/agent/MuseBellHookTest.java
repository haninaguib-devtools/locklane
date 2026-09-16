package dev.locklane.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Covers #928's done-when: {@code <data-dir>/hooks/muse-bell.sh} and {@code
 * <data-dir>/hooks/muse-hooks.json} exist once this bean is constructed (what happens
 * at engine startup), the hooks file names the script for exactly the four Muse Code
 * events the task record documents, the script rings a bell on its own controlling
 * terminal under the right payload conditions and hands the engine a session id on
 * {@code SessionStart}, and constructing it again — a second engine start against
 * the same data directory — is safe and overwrites whatever was there.
 *
 * <p>Muse Code itself is not installed in CI, so "running it" here means what this
 * repo can actually prove without a real Muse Code: the script, run under a real pty
 * (the same mechanism {@code PtySession} itself uses) in the shape Muse Code 1.3.0
 * runs it — the event's JSON payload on stdin, a scrubbed environment, the launching
 * terminal kept as its controlling terminal (confirmed against the installed binary,
 * see the task record) — writes what it should to that terminal and stays silent
 * otherwise.
 */
class MuseBellHookTest {

    private static final String SESSION_ID = "01a0a7d1-7496-7741-8eca-9c642d1f1478";
    private static final String BEL = "";
    private static final String ESC = "";

    @Test
    void installsAnExecutableScriptAndAHooksFileNamingItUnderHooks(@TempDir Path dataDir) throws Exception {
        MuseBellHook installed = new MuseBellHook(dataDir.toString());

        Path script = dataDir.resolve("hooks").resolve("muse-bell.sh");
        Path hooksFile = dataDir.resolve("hooks").resolve("muse-hooks.json");
        assertThat(installed.scriptPath()).isEqualTo(script);
        assertThat(installed.hooksFilePath()).isEqualTo(hooksFile);
        assertThat(script).exists().isExecutable();
        assertThat(Files.readString(script, StandardCharsets.UTF_8)).isEqualTo(MuseBellHook.SCRIPT_CONTENT);

        JsonNode hooks = new ObjectMapper().readTree(hooksFile.toFile()).path("hooks");
        assertThat(hooks.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("SessionStart", "Stop", "PermissionRequest", "Notification");
        for (String event : new String[] {"SessionStart", "Stop", "PermissionRequest", "Notification"}) {
            JsonNode handler = hooks.path(event).path(0).path("hooks").path(0);
            assertThat(handler.path("type").asText()).isEqualTo("command");
            assertThat(handler.path("command").asText()).isEqualTo(script.toString());
        }
    }

    @Test
    void constructingItAgainAgainstTheSameDataDirIsSafeAndLeavesTheSameFilesInPlace(@TempDir Path dataDir)
            throws Exception {
        new MuseBellHook(dataDir.toString());
        Path script = dataDir.resolve("hooks").resolve("muse-bell.sh");
        Path hooksFile = dataDir.resolve("hooks").resolve("muse-hooks.json");
        String firstScript = Files.readString(script, StandardCharsets.UTF_8);
        String firstHooksFile = Files.readString(hooksFile, StandardCharsets.UTF_8);

        MuseBellHook second = new MuseBellHook(dataDir.toString());

        assertThat(second.scriptPath()).isEqualTo(script);
        assertThat(Files.readString(script, StandardCharsets.UTF_8)).isEqualTo(firstScript);
        assertThat(Files.readString(hooksFile, StandardCharsets.UTF_8)).isEqualTo(firstHooksFile);
    }

    @Test
    void staleOrTamperedFilesAreOverwrittenRatherThanLeftAlone(@TempDir Path dataDir) throws Exception {
        Path hooksDir = Files.createDirectories(dataDir.resolve("hooks"));
        Files.writeString(hooksDir.resolve("muse-bell.sh"), "#!/bin/sh\nexit 1\n", StandardCharsets.UTF_8);
        Files.writeString(hooksDir.resolve("muse-hooks.json"), "{}", StandardCharsets.UTF_8);

        MuseBellHook installed = new MuseBellHook(dataDir.toString());

        assertThat(Files.readString(installed.scriptPath(), StandardCharsets.UTF_8)).isEqualTo(MuseBellHook.SCRIPT_CONTENT);
        assertThat(Files.readString(installed.hooksFilePath(), StandardCharsets.UTF_8))
                .isEqualTo(MuseBellHook.hooksFileContent(installed.scriptPath()));
    }

    @Test
    void aStopThatEndedForTheUserRingsTheBellOnItsOwnControllingTerminal(@TempDir Path dataDir) throws Exception {
        String output = runHook(dataDir, "{\"hook_event_name\":\"Stop\",\"stop_hook_active\":false,\"session_id\":\""
                + SESSION_ID + "\"}");

        assertThat(output).isEqualTo(BEL);
    }

    @Test
    void aStopMuseIsAboutToContinueOnItsOwnDoesNotRing(@TempDir Path dataDir) throws Exception {
        String output = runHook(dataDir, "{\"hook_event_name\":\"Stop\",\"stop_hook_active\":true,\"session_id\":\""
                + SESSION_ID + "\"}");

        assertThat(output).isEmpty();
    }

    @Test
    void anApprovalRequestAndANotificationEachRing(@TempDir Path dataDir) throws Exception {
        assertThat(runHook(dataDir, "{\"hook_event_name\":\"PermissionRequest\",\"tool_name\":\"shell\"}"))
                .isEqualTo(BEL);
        assertThat(runHook(dataDir, "{\"hook_event_name\":\"Notification\",\"message\":\"Muse Code needs approval\"}"))
                .isEqualTo(BEL);
    }

    @Test
    void aSessionStartHandsTheEngineTheSessionIdAsAResumeCommandInsideADcsString(@TempDir Path dataDir)
            throws Exception {
        String output = runHook(dataDir, "{\"hook_event_name\":\"SessionStart\",\"source\":\"startup\",\"session_id\":\""
                + SESSION_ID + "\",\"cwd\":\"/somewhere\"}");

        // ESC P ... ESC \ -- what ResumeIdScanner reads through and the browser
        // terminal swallows; never a bell, since a session starting is not "waiting".
        assertThat(output).isEqualTo(ESC + "Plocklane;muse resume " + SESSION_ID + ESC + "\\");
    }

    @Test
    void anyOtherEventOrAnEmptyPayloadWritesNothingAndExitsZero(@TempDir Path dataDir) throws Exception {
        assertThat(runHook(dataDir, "{\"hook_event_name\":\"PostToolUse\",\"tool_name\":\"shell\"}")).isEmpty();
        assertThat(runHook(dataDir, "")).isEmpty();
    }

    /**
     * Runs the installed script the way Muse Code 1.3.0 does — {@code payload} on
     * stdin, under a real pty so it has a controlling terminal to write to — and
     * returns everything that arrived on that terminal before the script exited. The
     * payload comes from a file, never typed into the pty, so nothing is echoed: only
     * the script's own writes to {@code /dev/tty} are read back.
     */
    private static String runHook(Path dataDir, String payload) throws Exception {
        MuseBellHook installed = new MuseBellHook(dataDir.toString());
        Path payloadFile = dataDir.resolve("payload.json");
        Files.writeString(payloadFile, payload, StandardCharsets.UTF_8);
        PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[] {"/bin/sh", "-c",
                        "\"$1\" < \"$2\"; exit $?", "sh", installed.scriptPath().toString(), payloadFile.toString()})
                .start();
        try {
            String output = readUntilExit(process, Duration.ofSeconds(5));
            assertThat(process.exitValue()).as("hook exit status").isZero();
            return output;
        } finally {
            process.destroy();
        }
    }

    /**
     * Everything the process wrote to its terminal until it exited. A blocking read
     * on a background thread, not an {@code available()} poll -- see
     * {@code CodexBellHookScriptTest}; the read ends when the process's exit closes
     * the pty, and a still-open pty after the exit yields whatever arrived so far.
     */
    private static String readUntilExit(PtyProcess process, Duration timeout) throws Exception {
        InputStream in = process.getInputStream();
        CompletableFuture<String> everything = CompletableFuture.supplyAsync(() -> {
            byte[] buffer = new byte[4096];
            StringBuilder collected = new StringBuilder();
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    collected.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // silent: the process ending closes this stream; nothing more to read.
            }
            return collected.toString();
        });
        assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as("hook exited within timeout").isTrue();
        try {
            return everything.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return everything.getNow("");
        }
    }
}
