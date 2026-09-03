package dev.locklane.engine.codeserver;

import dev.locklane.engine.pty.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Starts and stops one code-server (open-source, web-based VS Code, #627) process per
 * console, bound to {@code 127.0.0.1} only, with the working directory resolved
 * server-side from the console id via {@link SessionRegistry} — the same lookup {@code
 * FileManagerLauncher} and PTY spawning itself already use, so the browser never
 * supplies a path. A second {@link #start} for the same console reuses the process
 * already running rather than starting a second one; the process is stopped when its
 * console's session ends (registered as a {@link SessionRegistry} close listener, since
 * every session closer already funnels through {@link SessionRegistry#close}).
 *
 * <p>Since #655 the loopback address a process listens on is never what a browser is
 * given: the engine reverse-proxies each console's IDE under
 * {@code /api/projects/{projectId}/consoles/{id}/ide/} ({@link CodeServerHttpProxy},
 * {@link CodeServerWebSocketProxy}), behind locklane's own session and owner-only
 * check, so a remote browser reaches it on the engine's own host and port. What this
 * service hands out is the loopback base ({@link #start}, {@link #upstream}) those
 * proxies forward to.
 */
@Service
public class CodeServerService {

    private static final Logger log = LoggerFactory.getLogger(CodeServerService.class);

    /**
     * Spawns a subprocess — injected so a test can assert on the command without
     * spawning one. Public, unlike {@code FileManagerLauncher}'s own private twin,
     * because this service's controller ({@code ConsolesController}) is tested from a
     * different package.
     */
    public interface ProcessRunner {
        Process run(String... command) throws IOException;
    }

    private static final ProcessRunner DEFAULT_RUNNER = command -> new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();

    private final SessionRegistry sessionRegistry;
    private final Path codeServerBinary;
    private final ProcessRunner processRunner;
    private final ConcurrentMap<String, Running> running = new ConcurrentHashMap<>();

    @Autowired
    public CodeServerService(SessionRegistry sessionRegistry, @Value("${locklane.data-dir}") String dataDir) {
        this(sessionRegistry, Path.of(dataDir, "code-server", "bin", "code-server"), DEFAULT_RUNNER);
    }

    /** Test-only: an injected binary path and {@link ProcessRunner}, never spawning a real subprocess. */
    public CodeServerService(SessionRegistry sessionRegistry, Path codeServerBinary, ProcessRunner processRunner) {
        this.sessionRegistry = sessionRegistry;
        this.codeServerBinary = codeServerBinary;
        this.processRunner = processRunner;
        sessionRegistry.addCloseListener(this::stop);
    }

    /**
     * Starts (or reuses) code-server for {@code consoleId}'s worktree and returns the
     * loopback base the engine's proxy forwards to ({@code http://127.0.0.1:<port>}).
     * Empty, with nothing started, when the console id names no known working
     * directory.
     */
    public Optional<URI> start(String consoleId) {
        Running existing = running.get(consoleId);
        if (existing != null) {
            return Optional.of(existing.upstream());
        }
        Optional<Path> workingDirectory = sessionRegistry.lastKnownWorkingDirectory(consoleId);
        if (workingDirectory.isEmpty()) {
            return Optional.empty();
        }
        // computeIfAbsent, not the plain get-then-put above, is what makes a second
        // concurrent start() for the same console reuse one process rather than a race
        // spawning two — the up-front get() above is only a fast path once one exists.
        Running started = running.computeIfAbsent(consoleId, id -> spawn(workingDirectory.get()));
        return Optional.of(started.upstream());
    }

    /**
     * The loopback base of {@code consoleId}'s already-running code-server, or empty
     * when none is running — never starts one. The proxies resolve their target
     * through this: an IDE nobody asked to open (or whose console has since closed,
     * which stops it) is not reachable, the same as a console that does not exist.
     */
    public Optional<URI> upstream(String consoleId) {
        return Optional.ofNullable(running.get(consoleId)).map(Running::upstream);
    }

    private Running spawn(Path workingDirectory) {
        int port = allocatePort();
        try {
            Process process = processRunner.run(
                    codeServerBinary.toString(),
                    "--bind-addr", "127.0.0.1:" + port,
                    // Bound to loopback only (above): the only client that ever reaches
                    // this process is the engine's own proxy (#655), which admits a
                    // request only once locklane's session and owner-only check have
                    // passed (CONSTITUTION.md §4.5). code-server's own password prompt
                    // would add a second login without adding a second boundary, so it
                    // stays off -- which is also exactly why this bind address must never
                    // widen: with no auth of its own, a network-reachable code-server
                    // would be an unauthenticated shell in the worktree.
                    "--auth", "none",
                    "--disable-telemetry",
                    workingDirectory.toString());
            return new Running(process, port);
        } catch (IOException e) {
            log.warn("Could not start code-server at {}", workingDirectory, e);
            throw new CodeServerLaunchException(e);
        }
    }

    /** Stops {@code consoleId}'s running code-server process, if any. A no-op otherwise. */
    public void stop(String consoleId) {
        Running stopped = running.remove(consoleId);
        if (stopped != null) {
            stopped.process().destroy();
        }
    }

    /**
     * An OS-assigned free port, released immediately so code-server can bind it — a
     * brief window in which another process could take it first, accepted the same
     * way this pattern is everywhere: {@code --bind-addr 0} would return code-server's
     * chosen port only through a log line, not a value this service could read back.
     */
    private static int allocatePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new CodeServerLaunchException(e);
        }
    }

    private record Running(Process process, int port) {
        URI upstream() {
            return URI.create("http://127.0.0.1:" + port);
        }
    }

    /** Wraps a failure to even start code-server. */
    public static class CodeServerLaunchException extends RuntimeException {
        CodeServerLaunchException(Exception cause) {
            super(cause);
        }
    }
}
