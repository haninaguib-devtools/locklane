package dev.locklane.engine.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materialises {@code <data-dir>/hooks/omp-bell.js} at startup (#857): an OMP
 * extension file Locklane owns, loaded on every {@code omp} launch via {@code
 * --hook=<path>} ({@code TerminalWebSocketHandler}). OMP's extension API and event
 * names are undocumented in any form this task could cite, so they were read
 * directly out of the installed OMP binary (omp-cli v18.1.10) — see the task record
 * (docs/tasks/857-*.md) for how each event below was confirmed, including OMP's own
 * bundled telemetry extension, which hooks the identical three events for the
 * identical purpose (deciding when to notify).
 *
 * <p>Once loaded, the extension rings the same bare bell {@code
 * dev.locklane.engine.pty.PtySession} scans for (#130) on three OMP extension events,
 * each meaning "stopped, waiting for the user" the same way ADR-113 established for
 * Claude Code:
 * <ul>
 *   <li>{@code agent_end}, only when the event's {@code willContinue} is falsy — a
 *       turn actually ended in prose, not a step inside a longer multi-step
 *       continuation OMP is about to keep running on its own;</li>
 *   <li>{@code tool_approval_requested} — an approval prompt about to be shown;</li>
 *   <li>{@code tool_execution_start} where the tool name is {@code ask} — OMP's own
 *       built-in structured-question tool about to be shown.</li>
 * </ul>
 *
 * <p>Written unconditionally on every construction (once per engine start, as a
 * singleton bean), not only when absent — the same "always correct, never merely
 * present" idempotence {@link CodexBellHookScript} uses, for the same reason: a
 * stale extension from an older running version must never survive a restart.
 */
@Component
public class OmpBellHookExtension {

    static final String CONTENT = """
            "use strict";

            // Locklane (#857): rings the engine's own bell (dev.locklane.engine.pty.PtySession,
            // #130) on OMP's own extension events that mean "stopped, waiting for the user" --
            // a turn ending, an approval prompt, or OMP's built-in "ask" tool being shown.
            // Writes directly to the controlling terminal, not console/stdout, the same
            // contract ADR-113 established for Claude Code's own hooks.

            function ringBell() {
              let fd;
              try {
                fd = require("fs").openSync("/dev/tty", "w");
                require("fs").writeSync(fd, "\\u0007");
              } catch {
                // silent: no controlling terminal to ring a bell on -- nothing productive
                // to do with that here.
              } finally {
                if (fd !== undefined) {
                  try {
                    require("fs").closeSync(fd);
                  } catch {
                    // silent: already best-effort.
                  }
                }
              }
            }

            module.exports = function (api) {
              api.on("agent_end", (event) => {
                if (event && event.willContinue) {
                  return;
                }
                ringBell();
              });
              api.on("tool_approval_requested", () => {
                ringBell();
              });
              api.on("tool_execution_start", (event) => {
                if (event && event.toolName === "ask") {
                  ringBell();
                }
              });
            };
            """;

    private final Path extensionPath;

    @Autowired
    public OmpBellHookExtension(@Value("${locklane.data-dir}") String dataDir) throws IOException {
        this(Path.of(dataDir).resolve("hooks").resolve("omp-bell.js"));
    }

    /** Package-visible so a test can point this at a temp directory. */
    OmpBellHookExtension(Path extensionPath) throws IOException {
        this.extensionPath = extensionPath;
        Files.createDirectories(extensionPath.getParent());
        Files.writeString(extensionPath, CONTENT, StandardCharsets.UTF_8);
    }

    /** Where the extension lives — what {@code TerminalWebSocketHandler} names in every {@code omp} launch's {@code --hook}. */
    public Path extensionPath() {
        return extensionPath;
    }
}
