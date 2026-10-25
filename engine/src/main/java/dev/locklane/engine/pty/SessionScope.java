package dev.locklane.engine.pty;

import dev.locklane.engine.process.ProcessOutcome;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where a session's process tree runs on Linux (#1015): its own transient scope under
 * {@value #SLICE}, the slice whose unit {@code scripts/locklane} writes with the memory
 * limit all sessions share. When the sessions together reach it, the kernel kills a
 * process inside the slice, and {@code systemd-oomd} picks one session's scope -- never
 * {@code locklane.service}, the engine and every other session with it. The pattern is
 * {@code DesktopIdeLauncher}'s, with the slice named.
 *
 * <p>{@code systemd-run} has the user manager move its own process into the scope and
 * then {@code exec}s the command, so the session keeps one pid and the engine still sees
 * it exit. It is used only where it works: on Linux, on {@code PATH}, and once the
 * engine has seen it start a scope in that slice -- a host with no user manager (a CI
 * runner, a container) would otherwise have every session exit at once. The probe runs
 * once, on the first session; elsewhere sessions start as before, in the engine's own
 * control group.
 */
final class SessionScope {

    private static final Logger log = LoggerFactory.getLogger(SessionScope.class);

    static final String SLICE = "locklane-sessions.slice";

    private static final long PROBE_TIMEOUT_SECONDS = 10;

    // Resolved once, by the first session to start: null until then.
    private static volatile Optional<String> systemdRun;

    private SessionScope() {
    }

    /**
     * The {@code systemd-run} prefix that starts a command in its own scope under
     * {@value #SLICE}; empty where it cannot -- including a session whose {@code
     * environment} lacks {@code XDG_RUNTIME_DIR}, through which {@code systemd-run}
     * reaches the user manager ("Failed to connect to bus" without it). A session's
     * environment is the engine's own plus additions, so in practice that is only a test
     * that starts one with an empty environment.
     */
    static String[] prefix(String sessionId, Map<String, String> environment) {
        String runtimeDir = environment.get("XDG_RUNTIME_DIR");
        if (runtimeDir == null || runtimeDir.isBlank()) {
            return new String[0];
        }
        Optional<String> resolved = systemdRun;
        if (resolved == null) {
            synchronized (SessionScope.class) {
                if (systemdRun == null) {
                    systemdRun = probe();
                }
                resolved = systemdRun;
            }
        }
        return resolved.map(path -> prefix(path, sessionId)).orElse(new String[0]);
    }

    static String[] prefix(String systemdRunPath, String sessionId) {
        return new String[] {systemdRunPath, "--user", "--scope", "--quiet", "--collect", "--slice=" + SLICE,
                "--description=locklane session " + sessionId};
    }

    private static Optional<String> probe() {
        if (!Files.exists(Path.of("/proc/self/cgroup"))) {
            return Optional.empty();
        }
        Optional<String> onPath = onPath(System.getenv("PATH"));
        if (onPath.isEmpty()) {
            log.info("systemd-run is not on PATH: sessions run in the engine's own control group, outside {}", SLICE);
            return Optional.empty();
        }
        String systemdRunPath = onPath.get();
        String[] command = new String[] {systemdRunPath, "--user", "--scope", "--quiet", "--collect", "--slice=" + SLICE,
                "true"};
        try {
            Process process = new ProcessBuilder(command).start();
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("{} did not finish within {}s: sessions run in the engine's own control group, outside {}",
                        String.join(" ", command), PROBE_TIMEOUT_SECONDS, SLICE);
                return Optional.empty();
            }
            ProcessOutcome outcome = new ProcessOutcome(process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            if (outcome.failed()) {
                log.warn("{} exited {}: {} -- sessions run in the engine's own control group, outside {}",
                        String.join(" ", command), outcome.exitCode(), outcome.describe(), SLICE);
                return Optional.empty();
            }
        } catch (IOException e) {
            log.warn("Could not run {}: sessions run in the engine's own control group, outside {}",
                    String.join(" ", command), SLICE, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted probing {}: sessions run in the engine's own control group, outside {}",
                    String.join(" ", command), SLICE, e);
            return Optional.empty();
        }
        log.info("Sessions run in their own scopes under {}", SLICE);
        return Optional.of(systemdRunPath);
    }

    /** The first executable {@code systemd-run} on {@code pathEnv}, as an absolute path. */
    private static Optional<String> onPath(String pathEnv) {
        if (pathEnv == null) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File file = new File(dir, "systemd-run");
            if (file.isFile() && file.canExecute()) {
                return Optional.of(file.getAbsolutePath());
            }
        }
        return Optional.empty();
    }
}
