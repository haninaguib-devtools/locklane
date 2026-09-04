package dev.locklane.engine.codeserver;

import dev.locklane.engine.process.ProcessTrees;
import dev.locklane.engine.pty.SessionRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /**
     * Runs {@link #stop}'s termination off the caller's thread, since that caller is a
     * {@link SessionRegistry} close listener ({@code sessionRegistry.close()} invokes
     * every listener in turn) and terminating a tree that ignores SIGTERM can take the
     * full grace-plus-forced-wait before it returns — up to 7s the console-close path
     * has no reason to sit through. {@link #stopAll()} never uses this: shutdown is
     * exactly what that bounded wait is for, so it terminates synchronously and closes
     * this worker afterward, which waits for any stop() still in flight.
     */
    private final ExecutorService stopWorker = Executors.newVirtualThreadPerTaskExecutor();

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

    /**
     * Stops {@code consoleId}'s running code-server process, if any — the whole tree
     * it spawned, not just the node process the engine started. A no-op otherwise.
     * Returns as soon as the process is no longer tracked; the termination itself
     * (which can take up to the grace period plus a forced wait) runs in the
     * background (#682) so the caller — a {@link SessionRegistry} close listener — is
     * never held up by a code-server that ignores SIGTERM.
     */
    public void stop(String consoleId) {
        Running stopped = running.remove(consoleId);
        if (stopped != null) {
            stopWorker.execute(() -> terminate(List.of(stopped)));
        }
    }

    /**
     * Stops every running code-server when the engine shuts down (#678). On Linux the
     * service's control group would sweep these up anyway; on macOS launchd signals
     * only the JVM, and without this every IDE the engine ever opened would outlive
     * it, holding its port and its worktree. Unlike {@link #stop}, this runs
     * synchronously — shutdown is exactly what the bounded wait exists for — and then
     * closes the worker {@link #stop} uses, which blocks until any termination still
     * in flight from it has finished.
     */
    @PreDestroy
    public void stopAll() {
        List<Running> all = new ArrayList<>(running.values());
        running.clear();
        if (!all.isEmpty()) {
            terminate(all);
        }
        stopWorker.close();
    }

    private static final Duration STOP_GRACE = Duration.ofSeconds(5);

    private void terminate(List<Running> processes) {
        List<ProcessHandle> handles = processes.stream().map(r -> r.process().toHandle()).toList();
        List<ProcessHandle> left = ProcessTrees.terminate(handles, STOP_GRACE);
        if (!left.isEmpty()) {
            log.warn("code-server processes still alive after being stopped: {}",
                    left.stream().map(ProcessHandle::pid).toList());
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
