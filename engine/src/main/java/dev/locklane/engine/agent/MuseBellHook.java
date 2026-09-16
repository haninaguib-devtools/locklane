package dev.locklane.engine.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Materialises Muse Code's bell hook at startup (#928): {@code <data-dir>/hooks/muse-bell.sh},
 * the script, and {@code <data-dir>/hooks/muse-hooks.json}, the Muse Code hooks file
 * naming it. {@code TerminalWebSocketHandler} attaches the hooks file to every
 * {@code muse} launch through {@code TBH_MANAGED_HOOKS_PATH}, Muse Code's own
 * "managed hooks" file variable — read out of the installed Muse Code 1.3.0 binary
 * and confirmed live against it (see the task record, docs/tasks/928-*.md): a hooks
 * file named there loads on every launch, trusted workspace or not, without touching
 * {@code ~/.config/muse/} or any workspace's {@code .muse/}.
 *
 * <p>The hooks file uses the Claude Code-compatible shape Muse Code 1.3.0 accepts —
 * {@code {"hooks": {"<Event>": [{"hooks": [{"type": "command", "command": ...}]}]}}}
 * — and names the script for four events. Muse Code runs the script with the event's
 * JSON payload on standard input and an environment scrubbed down to an allowlist
 * ({@code PATH}, {@code HOME}, {@code TERM}, ...), so {@code LOCKLANE_TTY} never
 * reaches it; but, unlike Claude Code's and Codex's hooks (#904), the child keeps the
 * launching terminal as its controlling terminal, so the script writes to
 * {@code /dev/tty} directly — inside a Locklane tab, the engine's own PTY, exactly what
 * {@code dev.locklane.engine.pty.PtySession}'s bell scanner watches (#130).
 * <ul>
 *   <li>{@code Stop} — a turn ended for the user: rings the bell unless the payload's
 *       {@code stop_hook_active} is {@code true}, Muse Code's flag for a stop it is
 *       about to continue on its own (a stop-hook continuation), which must not ring;</li>
 *   <li>{@code PermissionRequest} — an approval is wanted: rings;</li>
 *   <li>{@code Notification} — Muse Code needs attention: rings;</li>
 *   <li>{@code SessionStart} — no bell: Muse Code's TUI never prints its own session
 *       id, so this hook writes the payload's {@code session_id} to the terminal as
 *       {@code muse resume <id>} inside a DCS string (ESC P ... ESC \), which the
 *       browser terminal swallows whole while {@code ResumeIdScanner}, which strips
 *       only the ESC bytes, still reads the resume command in between (#102).</li>
 * </ul>
 *
 * <p>Written unconditionally on every construction (once per engine start, as a
 * singleton bean), not only when absent — the same "always correct, never merely
 * present" idempotence {@link CodexBellHookScript} uses, for the same reason: a
 * stale script from an older running version must never survive a restart.
 */
@Component
public class MuseBellHook {

    static final String SCRIPT_CONTENT = """
            #!/bin/sh
            # Locklane (#928): Muse Code's bell hook. Muse Code runs this with one event's
            # JSON payload on stdin and a scrubbed environment (no LOCKLANE_TTY), but the
            # child keeps the launching terminal as its controlling terminal, so writes go
            # to /dev/tty -- inside a Locklane tab, the engine's own PTY, what
            # dev.locklane.engine.pty.PtySession's bell scanner watches (#130).
            # Best-effort throughout: nothing here may print to stdout/stderr (a hook's
            # output reaches Muse Code, never the screen) or exit non-zero.
            payload=$(cat)
            case "$payload" in
              *'"hook_event_name":"SessionStart"'*)
                # Muse Code never prints its session id on screen; hand it to the engine's
                # ResumeIdScanner as `muse resume <id>` inside a DCS string, which the
                # browser terminal swallows whole and the scanner reads through.
                id=$(printf '%s' "$payload" | sed -n 's/.*"session_id":"\\([0-9a-fA-F-]*\\)".*/\\1/p')
                if [ -n "$id" ]; then
                  { printf '\\033Plocklane;muse resume %s\\033\\\\' "$id" > /dev/tty; } 2>/dev/null || true
                fi
                ;;
              *'"hook_event_name":"Stop"'*)
                # A stop Muse Code is about to continue on its own must not ring.
                case "$payload" in
                  *'"stop_hook_active":true'*) ;;
                  *) { printf '\\a' > /dev/tty; } 2>/dev/null || true ;;
                esac
                ;;
              *'"hook_event_name":"PermissionRequest"'*|*'"hook_event_name":"Notification"'*)
                { printf '\\a' > /dev/tty; } 2>/dev/null || true
                ;;
            esac
            exit 0
            """;

    private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");

    private final Path scriptPath;
    private final Path hooksFilePath;

    @Autowired
    public MuseBellHook(@Value("${locklane.data-dir}") String dataDir) throws IOException {
        this(Path.of(dataDir).resolve("hooks"));
    }

    /** Package-visible so a test can point this at a temp directory. */
    MuseBellHook(Path hooksDir) throws IOException {
        this.scriptPath = hooksDir.resolve("muse-bell.sh");
        this.hooksFilePath = hooksDir.resolve("muse-hooks.json");
        Files.createDirectories(hooksDir);
        Files.writeString(scriptPath, SCRIPT_CONTENT, StandardCharsets.UTF_8);
        makeExecutable(scriptPath);
        Files.writeString(hooksFilePath, hooksFileContent(scriptPath), StandardCharsets.UTF_8);
    }

    /** The hooks file's content for a script at {@code scriptPath}: one handler per event, all the same script. */
    static String hooksFileContent(Path scriptPath) {
        String handler = "[{\"hooks\":[{\"type\":\"command\",\"command\":" + jsonString(scriptPath.toString()) + "}]}]";
        return "{\"hooks\":{"
                + "\"SessionStart\":" + handler + ","
                + "\"Stop\":" + handler + ","
                + "\"PermissionRequest\":" + handler + ","
                + "\"Notification\":" + handler
                + "}}\n";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Where the script lives — what the hooks file names as every event's command. */
    public Path scriptPath() {
        return scriptPath;
    }

    /** Where the hooks file lives — what {@code TerminalWebSocketHandler} names in every {@code muse} launch's {@code TBH_MANAGED_HOOKS_PATH}. */
    public Path hooksFilePath() {
        return hooksFilePath;
    }

    /** No-op where the filesystem has no POSIX permission model (e.g. Windows). */
    private static void makeExecutable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EXECUTABLE);
        } catch (UnsupportedOperationException e) {
            // silent: best effort -- not every filesystem supports POSIX permissions.
        }
    }
}
