package dev.locklane.engine.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Places {@code locklane-bell.js} in OpenCode's global plugin directory at startup
 * (#858): unlike Claude Code, Codex and OMP, OpenCode has no launch-time flag to load
 * a local plugin file — local plugins are read only from
 * {@code <config-dir>/plugins/} ({@code <config-dir>} is {@code $OPENCODE_CONFIG_DIR}
 * when set, else {@code ~/.config/opencode} — {@code locklane.opencode-config-dir} in
 * application.yml, OpenCode's own resolution rule spelled out as one Spring property
 * so a test can override the whole thing, the same way {@code locklane.data-dir}
 * already is), never from an argv the engine composes. {@code TerminalWebSocketHandler}
 * depends on this bean purely so its installation happens as part of the same startup
 * sequence documented alongside the other three agents' bell wiring — there is no argv
 * for OpenCode to change.
 *
 * <p>Confirmed against the installed {@code @opencode-ai/plugin} package (v1.18.25):
 * a plugin file exports an async {@code Plugin} function, {@code (input, options?) =>
 * Promise<Hooks>} (its own bundled example uses a named export, not {@code export
 * default}); the two hooks that mean "stopped, waiting for the user" are the generic
 * {@code event} hook, filtered to {@code event.type === "session.idle"} (a turn
 * ended), and {@code "permission.ask"} (an approval prompt about to be shown) — the
 * issue that opened this task said {@code permission.asked}; the SDK's own {@code
 * Hooks} interface names it {@code permission.ask}, corrected here against that
 * authoritative source.
 *
 * <p>This is the one file in that directory the engine may ever touch, and only when
 * it already carries the {@link #HEADER} this class writes: a file with the same
 * name but no such header is a user's own file, left alone entirely, forever — see
 * {@link #install}. A file that already carries the header is freely refreshed
 * (never merely left because it exists), the same "idempotent means always correct"
 * contract {@link CodexBellHookScript} and {@link OmpBellHookExtension} use, except
 * skipped when its content already matches exactly, to avoid an unnecessary write
 * every single startup.
 */
@Component
public class OpenCodeBellPlugin {

    /**
     * The first line of every file this class writes, and the sole test for whether
     * an existing file is safe to overwrite — anything else on that line (including
     * no file at all past this check) means "not ours."
     */
    static final String HEADER = "// Locklane-managed OpenCode plugin (#858) -- regenerated automatically, do not edit by hand.";

    // CommonJS, deliberately, not the ESM `export const` OpenCode's own bundled
    // example plugin uses: a bare `.js` file with no package.json declaring it as a
    // module is CommonJS by Node's own resolution rules regardless of the syntax
    // inside it, so `export const` here would be a syntax error the moment anything
    // (including OpenCode's own loader, however it resolves this file) parses it
    // as the CommonJS Node/Bun default a stray file with no package.json gets.
    // `require("node:fs")` needs no import statement that would only be legal in an
    // actual ES module.
    static final String PLUGIN_BODY = """
            module.exports.LocklaneBellPlugin = async () => {
              function ringBell() {
                let fd;
                try {
                  fd = require("node:fs").openSync("/dev/tty", "w");
                  require("node:fs").writeSync(fd, "\\u0007");
                } catch {
                  // silent: no controlling terminal to ring a bell on -- nothing
                  // productive to do with that here.
                } finally {
                  if (fd !== undefined) {
                    try {
                      require("node:fs").closeSync(fd);
                    } catch {
                      // silent: already best-effort.
                    }
                  }
                }
              }
              return {
                event: async ({ event }) => {
                  if (event.type === "session.idle") {
                    ringBell();
                  }
                },
                "permission.ask": async () => {
                  ringBell();
                },
              };
            };
            """;

    private final Path pluginPath;
    private final boolean installed;

    /**
     * {@code locklane.opencode-config-dir} (application.yml) is
     * {@code ${OPENCODE_CONFIG_DIR:${user.home}/.config/opencode}} — OpenCode's own
     * resolution rule, resolved by Spring rather than read from the environment
     * directly here, so a test can override the whole thing to a temp directory the
     * same way {@code locklane.data-dir} already is (see
     * {@code engine/src/test/resources/application.yml}) instead of this bean
     * writing into a real developer machine's actual OpenCode config on every
     * {@code @SpringBootTest}.
     */
    @Autowired
    public OpenCodeBellPlugin(@Value("${locklane.opencode-config-dir}") String configDir) throws IOException {
        this(Path.of(configDir).resolve("plugins").resolve("locklane-bell.js"));
    }

    /** Package-visible so a test can point this at a temp directory. */
    OpenCodeBellPlugin(Path pluginPath) throws IOException {
        this.pluginPath = pluginPath;
        Files.createDirectories(pluginPath.getParent());
        String content = content();
        if (Files.exists(pluginPath)) {
            String existing = Files.readString(pluginPath, StandardCharsets.UTF_8);
            if (!existing.startsWith(HEADER)) {
                // Not ours: a file the user placed here themselves, under the same
                // name -- left alone entirely, never read again past this check.
                this.installed = false;
                return;
            }
            if (existing.equals(content)) {
                // Already current: skip the write rather than touching mtime for no
                // reason every single engine start.
                this.installed = true;
                return;
            }
        }
        Files.writeString(pluginPath, content, StandardCharsets.UTF_8);
        this.installed = true;
    }

    /** Where the plugin lives — {@code <config-dir>/plugins/locklane-bell.js}. */
    public Path pluginPath() {
        return pluginPath;
    }

    /** False only when a foreign, non-Locklane file already occupied {@link #pluginPath}. */
    public boolean installed() {
        return installed;
    }

    private static String content() {
        return HEADER + "\n// content-sha256:" + sha256Hex(PLUGIN_BODY) + "\n\n" + PLUGIN_BODY;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Unreachable: every JDK ships SHA-256 (JLS-mandated MessageDigest algorithm).
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
