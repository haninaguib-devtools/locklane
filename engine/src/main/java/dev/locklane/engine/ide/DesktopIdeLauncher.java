package dev.locklane.engine.ide;

import dev.locklane.engine.agent.InstalledAgentDetector;
import dev.locklane.engine.pty.SessionRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Opens a console's worktree in a desktop IDE on the engine host (#781) — VS Code or
 * IntelliJ IDEA, the way {@code FileManagerLauncher} opens it in the OS file manager
 * for "Folder". The worktree is resolved server-side from the console id via
 * {@link SessionRegistry}, so the client never sends a path and cannot open an
 * arbitrary one; which IDE, and how it was found, comes from {@link InstalledIdesStore}
 * through the {@link InstalledIde} the caller passes.
 *
 * <p>The editor is never tracked or stopped by the engine, unlike code-server: it is
 * the person's own desktop program, opened on their behalf. On Linux, where the engine
 * runs as a systemd service, a plain child process would sit in the service's control
 * group and be swept up by {@code locklane stop}; when {@code systemd-run} is on
 * {@code PATH} the launch is moved into its own transient scope instead
 * ({@link #detached}), so a cold-launched editor outlives the engine.
 */
@Service
public class DesktopIdeLauncher {

    private static final Logger log = LoggerFactory.getLogger(DesktopIdeLauncher.class);

    /** Spawns a subprocess — injected so a test can assert on the command without spawning one. */
    interface ProcessRunner {
        void run(String... command) throws IOException;
    }

    private static final ProcessRunner DEFAULT_RUNNER = command -> new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();

    private static final BooleanSupplier SYSTEMD_RUN_ON_PATH = () -> !InstalledAgentDetector
            .detect(System.getenv("PATH"), new String[] {"systemd-run"}).isEmpty();

    private final SessionRegistry sessionRegistry;
    private final ProcessRunner processRunner;
    private final String osName;
    private final BooleanSupplier systemdRunOnPath;

    @Autowired
    public DesktopIdeLauncher(SessionRegistry sessionRegistry) {
        this(sessionRegistry, DEFAULT_RUNNER, System.getProperty("os.name", ""), SYSTEMD_RUN_ON_PATH);
    }

    /** Test-only: an injected {@link ProcessRunner}, OS name and {@code systemd-run} presence, never spawning a real subprocess. */
    DesktopIdeLauncher(SessionRegistry sessionRegistry, ProcessRunner processRunner, String osName,
            BooleanSupplier systemdRunOnPath) {
        this.sessionRegistry = sessionRegistry;
        this.processRunner = processRunner;
        this.osName = osName;
        this.systemdRunOnPath = systemdRunOnPath;
    }

    /**
     * Launches {@code ide} on {@code consoleId}'s worktree; {@code false} and no launch
     * at all when the console id names no known working directory.
     */
    public boolean launch(String consoleId, InstalledIde ide) {
        Optional<Path> workingDirectory = sessionRegistry.lastKnownWorkingDirectory(consoleId);
        if (workingDirectory.isEmpty()) {
            return false;
        }
        String[] command = detached(osName, systemdRunOnPath.getAsBoolean(),
                launchCommand(osName, ide, workingDirectory.get()));
        try {
            processRunner.run(command);
        } catch (IOException e) {
            log.warn("Could not launch {} for console {} at {}", ide.id(), consoleId, workingDirectory.get(), e);
            throw new DesktopIdeLaunchException(e);
        }
        log.info("Launched {} for console {} at {}", ide.id(), consoleId, workingDirectory.get());
        return true;
    }

    /** The IDE's own command for {@code path} on {@code osName} — a pure function of the three, read from {@link KnownIdes}. */
    static String[] launchCommand(String osName, InstalledIde ide, Path path) {
        return KnownIdes.launchCommand(HostOs.of(osName), ide, path);
    }

    /**
     * {@code command} as it is actually spawned: on Linux with {@code systemd-run}
     * available, {@code systemd-run --user --scope --quiet --collect <command>} — a
     * transient scope in the user's own manager, outside the engine service's cgroup,
     * {@code --collect} so a scope whose command failed does not linger — otherwise
     * {@code command} unchanged (a plain spawn, which on Linux stays in the engine's
     * cgroup and goes down with it).
     */
    static String[] detached(String osName, boolean systemdRunOnPath, String[] command) {
        if (HostOs.of(osName) != HostOs.LINUX || !systemdRunOnPath) {
            return command;
        }
        String[] wrapped = new String[command.length + 5];
        wrapped[0] = "systemd-run";
        wrapped[1] = "--user";
        wrapped[2] = "--scope";
        wrapped[3] = "--quiet";
        wrapped[4] = "--collect";
        System.arraycopy(command, 0, wrapped, 5, command.length);
        return wrapped;
    }

    /** Wraps a failure to even start the IDE process. */
    public static class DesktopIdeLaunchException extends RuntimeException {
        DesktopIdeLaunchException(IOException cause) {
            super(cause);
        }
    }
}
