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
 * Covers #858's done-when: the plugin file exists in OpenCode's global plugin
 * directory once this bean is constructed (what happens at engine startup, well
 * before any tab can launch {@code opencode}), idempotence (present and current is
 * left alone, stale is refreshed), a foreign file of the same name is never touched,
 * and the installed plugin's own two hooks each ring a bell on a real controlling
 * terminal under the right condition.
 *
 * <p>"Honouring {@code OPENCODE_CONFIG_DIR}" (also in the done-when) is
 * {@code locklane.opencode-config-dir}'s own job in application.yml
 * ({@code ${OPENCODE_CONFIG_DIR:${user.home}/.config/opencode}}, OpenCode's own
 * resolution rule spelled out declaratively) — Spring's own well-defined property
 * resolution, not bespoke logic this class needs a unit test of. What this class
 * does with whatever config directory it is handed is what these tests cover.
 */
class OpenCodeBellPluginTest {

    @Test
    void installsThePluginUnderConfigDirPlugins(@TempDir Path configDir) throws Exception {
        OpenCodeBellPlugin installed = new OpenCodeBellPlugin(configDir.resolve("plugins").resolve("locklane-bell.js"));

        Path expected = configDir.resolve("plugins").resolve("locklane-bell.js");
        assertThat(installed.pluginPath()).isEqualTo(expected);
        assertThat(installed.installed()).isTrue();
        assertThat(expected).exists();
        assertThat(Files.readString(expected, StandardCharsets.UTF_8)).startsWith(OpenCodeBellPlugin.HEADER);
    }

    @Test
    void resolvesThePluginUnderWhicheverConfigDirItIsGiven(@TempDir Path configDir) throws Exception {
        OpenCodeBellPlugin installed = new OpenCodeBellPlugin(configDir.toString());

        assertThat(installed.pluginPath()).isEqualTo(configDir.resolve("plugins").resolve("locklane-bell.js"));
        assertThat(installed.pluginPath()).exists();
    }

    @Test
    void constructingItAgainAgainstTheSamePathIsSafeAndLeavesThePluginInPlace(@TempDir Path configDir) throws Exception {
        Path pluginPath = configDir.resolve("plugins").resolve("locklane-bell.js");
        new OpenCodeBellPlugin(pluginPath);
        String firstContent = Files.readString(pluginPath, StandardCharsets.UTF_8);

        OpenCodeBellPlugin second = new OpenCodeBellPlugin(pluginPath);

        assertThat(second.installed()).isTrue();
        assertThat(Files.readString(pluginPath, StandardCharsets.UTF_8)).isEqualTo(firstContent);
    }

    @Test
    void aStalePreviousVersionIsRefreshedRatherThanLeftAlone(@TempDir Path configDir) throws Exception {
        Path pluginPath = configDir.resolve("plugins").resolve("locklane-bell.js");
        Files.createDirectories(pluginPath.getParent());
        Files.writeString(pluginPath,
                OpenCodeBellPlugin.HEADER + "\n// content-sha256:stale\n\nexport const LocklaneBellPlugin = async () => ({});\n",
                StandardCharsets.UTF_8);

        OpenCodeBellPlugin installed = new OpenCodeBellPlugin(pluginPath);

        assertThat(installed.installed()).isTrue();
        assertThat(Files.readString(pluginPath, StandardCharsets.UTF_8)).contains(OpenCodeBellPlugin.PLUGIN_BODY);
    }

    @Test
    void aForeignFileOfTheSameNameIsNeverTouched(@TempDir Path configDir) throws Exception {
        Path pluginPath = configDir.resolve("plugins").resolve("locklane-bell.js");
        Files.createDirectories(pluginPath.getParent());
        String foreignContent = "// my own plugin, nothing to do with Locklane\nexport const MyPlugin = async () => ({});\n";
        Files.writeString(pluginPath, foreignContent, StandardCharsets.UTF_8);

        OpenCodeBellPlugin installed = new OpenCodeBellPlugin(pluginPath);

        assertThat(installed.installed()).isFalse();
        assertThat(Files.readString(pluginPath, StandardCharsets.UTF_8)).isEqualTo(foreignContent);
    }

    @Test
    void aForeignFileNeverBlocksALaterUpdateOnceRemoved(@TempDir Path configDir) throws Exception {
        Path pluginPath = configDir.resolve("plugins").resolve("locklane-bell.js");
        Files.createDirectories(pluginPath.getParent());
        Files.writeString(pluginPath, "// someone else's file\n", StandardCharsets.UTF_8);
        assertThat(new OpenCodeBellPlugin(pluginPath).installed()).isFalse();

        Files.delete(pluginPath);

        assertThat(new OpenCodeBellPlugin(pluginPath).installed()).isTrue();
    }

    @Test
    void theSessionIdleEventRingsTheBellOnItsOwnControllingTerminal(@TempDir Path configDir) throws Exception {
        assertThat(ringsBellFor(configDir, "{ type: 'session.idle' }", null)).isTrue();
    }

    @Test
    void anUnrelatedEventDoesNotRing(@TempDir Path configDir) throws Exception {
        assertThat(ringsBellFor(configDir, "{ type: 'session.status' }", null)).isFalse();
    }

    @Test
    void thePermissionAskHookRingsTheBellOnItsOwnControllingTerminal(@TempDir Path configDir) throws Exception {
        assertThat(ringsBellFor(configDir, null, "{}")).isTrue();
    }

    /**
     * Loads the installed plugin as a real ES module under Node (the file commits to
     * ESM via {@code export const}, exactly as OpenCode's own example plugin does),
     * calls its factory, and fires either the generic {@code event} hook with {@code
     * eventLiteral} or the {@code "permission.ask"} hook (when {@code eventLiteral}
     * is null), reporting whether a bell appeared on the child process's own
     * controlling terminal within five seconds.
     */
    private static boolean ringsBellFor(Path configDir, String eventLiteral, String permissionAskArg) throws Exception {
        OpenCodeBellPlugin installed = new OpenCodeBellPlugin(configDir.resolve("plugins").resolve("locklane-bell.js"));
        Path driver = configDir.resolve("drive.mjs");
        String driverSource = eventLiteral != null
                ? """
                        import { LocklaneBellPlugin } from %s;
                        const hooks = await LocklaneBellPlugin();
                        await hooks.event({ event: %s });
                        """.formatted(jsStringLiteral(installed.pluginPath().toString()), eventLiteral)
                : """
                        import { LocklaneBellPlugin } from %s;
                        const hooks = await LocklaneBellPlugin();
                        await hooks["permission.ask"](%s, { status: "ask" });
                        """.formatted(jsStringLiteral(installed.pluginPath().toString()), permissionAskArg);
        Files.writeString(driver, driverSource, StandardCharsets.UTF_8);

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
     * True the moment a BEL (0x07) is read, false if the timeout passes with none —
     * see {@code CodexBellHookScriptTest} for why this blocks on {@code read} rather
     * than polling {@code available()}.
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
