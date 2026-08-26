package dev.locklane.engine.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One long-lived pseudo-terminal process for a single session, independent of any
 * client connection. A session's identity is its own — separate from whatever
 * working directory (main checkout or worktree) it happens to run in, so more than
 * one session can point at the same directory — and it runs whatever launch command
 * it was started with (a plain shell, or an agent CLI such as {@code claude} or
 * {@code codex}). A background thread drains its output into an in-memory buffer
 * continuously — regardless of whether a client is currently attached — so the
 * process never blocks on a full pipe, and a client that reattaches later sees
 * everything produced while it was gone. Live output is also pushed to any
 * currently-{@link #subscribe subscribed} listener, which is how a network transport
 * (e.g. WebSocket, #7) streams it to a browser in real time.
 */
public final class PtySession {

    // Fills gaps only (#63) — an explicit TERM/COLORTERM the caller's environment
    // already carries (e.g. the engine itself launched from a real terminal) is left
    // alone; this only rescues the common case where it is launched some other way
    // (an IDE run configuration, systemd, ...) and inherits neither at all, which
    // otherwise leaves every CLI running inside a session assuming no color support.
    private static final Map<String, String> DEFAULT_TERMINAL_ENV =
            Map.of("TERM", "xterm-256color", "COLORTERM", "truecolor");

    private final String sessionId;
    private final PtyProcess process;
    private final OutputBuffer output = new OutputBuffer();
    private final Set<OutputListener> listeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    PtySession(String sessionId, Path workingDirectory, String[] command, Map<String, String> environment,
            int initialColumns, int initialRows) {
        this.sessionId = sessionId;
        try {
            this.process = new PtyProcessBuilder()
                    .setCommand(command)
                    .setDirectory(workingDirectory.toString())
                    .setEnvironment(withDefaultTerminalEnv(environment))
                    .setInitialColumns(initialColumns)
                    .setInitialRows(initialRows)
                    .start();
        } catch (IOException e) {
            throw new PtySessionStartException(sessionId, e);
        }
        Thread drainThread = new Thread(this::drain, "pty-drain-" + sessionId);
        drainThread.setDaemon(true);
        drainThread.start();
    }

    private void drain() {
        InputStream in = process.getInputStream();
        byte[] chunk = new byte[4096];
        try {
            int n;
            while ((n = in.read(chunk)) != -1) {
                output.append(chunk, n);
                if (!listeners.isEmpty()) {
                    // Defensive copy: `chunk` is reused on the next loop iteration, so a
                    // listener that hands this off asynchronously must not see it mutate.
                    byte[] copy = Arrays.copyOf(chunk, n);
                    for (OutputListener listener : listeners) {
                        listener.onOutput(copy);
                    }
                }
            }
        } catch (IOException ignored) {
            // The process ended or its pty closed; nothing more to drain.
        }
    }

    public String sessionId() {
        return sessionId;
    }

    /** Everything the session has produced so far, from the start of the buffer. */
    public String bufferedOutput() {
        return output.snapshot();
    }

    public void write(String input) {
        OutputStream stdin = process.getOutputStream();
        try {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            throw new PtySessionIoException(sessionId, e);
        }
    }

    /**
     * Registers a listener for output produced from now on (past output is available
     * via {@link #bufferedOutput()}). Returns a handle whose {@code close()}
     * unsubscribes — callers must call it when they stop listening, or the listener
     * (and whatever it holds) leaks for the session's lifetime.
     */
    public AutoCloseable subscribe(OutputListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** Tells the running process its terminal changed size (SIGWINCH on Unix). */
    public void resize(int columns, int rows) {
        process.setWinSize(new WinSize(columns, rows));
    }

    private static Map<String, String> withDefaultTerminalEnv(Map<String, String> environment) {
        Map<String, String> merged = new HashMap<>(environment);
        DEFAULT_TERMINAL_ENV.forEach(merged::putIfAbsent);
        return merged;
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            process.destroy();
        }
    }

    @FunctionalInterface
    public interface OutputListener {
        void onOutput(byte[] chunk);
    }
}
