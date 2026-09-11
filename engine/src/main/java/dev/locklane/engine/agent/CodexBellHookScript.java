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
 * Materialises {@code <data-dir>/hooks/bell.sh} at startup (#856): a one-line script
 * Locklane owns, wired into Codex's own {@code notify} config
 * ({@code TerminalWebSocketHandler}) as the command Codex runs when an agent turn
 * completes. The script ignores the JSON payload Codex passes as its argument and
 * writes a bare bell to the controlling terminal — inside a Locklane tab, the
 * engine's own PTY, exactly what {@code dev.locklane.engine.pty.PtySession}'s bell
 * scanner watches (#130) — the same contract ADR-113 establishes for Claude Code's
 * own hooks.
 *
 * <p>Written unconditionally on every construction (once per engine start, as a
 * singleton bean), not only when absent: idempotent because the content and
 * permissions are always the same regardless of what was there before, so a stale
 * script from an older running version can never survive a restart on a newer one.
 */
@Component
public class CodexBellHookScript {

    static final String CONTENT = """
            #!/bin/sh
            # Locklane (#856): rings the engine's own bell (dev.locklane.engine.pty.PtySession,
            # #130) on the controlling terminal -- the JSON payload Codex passes as $1 when an
            # agent turn completes is ignored; this script's only job is the bell itself.
            printf '\\a' > /dev/tty
            """;

    private static final Set<PosixFilePermission> EXECUTABLE = PosixFilePermissions.fromString("rwxr-xr-x");

    private final Path scriptPath;

    @Autowired
    public CodexBellHookScript(@Value("${locklane.data-dir}") String dataDir) throws IOException {
        this(Path.of(dataDir).resolve("hooks").resolve("bell.sh"));
    }

    /** Package-visible so a test can point this at a temp directory. */
    CodexBellHookScript(Path scriptPath) throws IOException {
        this.scriptPath = scriptPath;
        Files.createDirectories(scriptPath.getParent());
        Files.writeString(scriptPath, CONTENT, StandardCharsets.UTF_8);
        makeExecutable(scriptPath);
    }

    /** Where the script lives — what {@code TerminalWebSocketHandler} names in Codex's {@code notify} override. */
    public Path scriptPath() {
        return scriptPath;
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
