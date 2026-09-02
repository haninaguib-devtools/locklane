package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Reveals a console's worktree in the OS's native file manager (#441) — resolved
 * server-side from the console id via {@link SessionRegistry}, the same lookup PTY
 * spawning itself uses for a session's working directory, so the client never sends a
 * path and can't be used to open an arbitrary one.
 */
@Service
public class FileManagerLauncher {

    private static final Logger log = LoggerFactory.getLogger(FileManagerLauncher.class);

    /** Spawns a subprocess — injected so a test can assert on the command without spawning one. */
    interface ProcessRunner {
        void run(String... command) throws IOException;
    }

    private static final ProcessRunner DEFAULT_RUNNER = command -> new ProcessBuilder(command).start();

    private final SessionRegistry sessionRegistry;
    private final ProcessRunner processRunner;

    @Autowired
    public FileManagerLauncher(SessionRegistry sessionRegistry) {
        this(sessionRegistry, DEFAULT_RUNNER);
    }

    /** Test-only: an injected {@link ProcessRunner}, never spawning a real subprocess. */
    FileManagerLauncher(SessionRegistry sessionRegistry, ProcessRunner processRunner) {
        this.sessionRegistry = sessionRegistry;
        this.processRunner = processRunner;
    }

    /**
     * Launches the file manager at {@code consoleId}'s worktree; {@code false} and no
     * launch at all when the console id names no known working directory.
     */
    public boolean reveal(String consoleId) {
        Optional<Path> workingDirectory = sessionRegistry.lastKnownWorkingDirectory(consoleId);
        if (workingDirectory.isEmpty()) {
            return false;
        }
        try {
            processRunner.run(revealCommand(System.getProperty("os.name", ""), workingDirectory.get()));
        } catch (IOException e) {
            log.warn("Could not launch the file manager for console {} at {}", consoleId, workingDirectory.get(), e);
            throw new FileManagerLaunchException(e);
        }
        return true;
    }

    /** `open` on macOS, `explorer.exe` on Windows, `xdg-open` everywhere else (Linux). */
    static String[] revealCommand(String osName, Path path) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac")) {
            return new String[] {"open", path.toString()};
        }
        if (lower.contains("win")) {
            return new String[] {"explorer.exe", path.toString()};
        }
        return new String[] {"xdg-open", path.toString()};
    }

    /** Wraps a failure to even start the file manager process. */
    public static class FileManagerLaunchException extends RuntimeException {
        FileManagerLaunchException(IOException cause) {
            super(cause);
        }
    }
}
