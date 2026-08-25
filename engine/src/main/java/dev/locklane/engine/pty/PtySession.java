package dev.locklane.engine.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One long-lived pseudo-terminal process for a single worktree, independent of any
 * client connection. A background thread drains its output into an in-memory buffer
 * continuously — regardless of whether a client is currently attached — so the
 * process never blocks on a full pipe, and a client that reattaches later sees
 * everything produced while it was gone.
 */
public final class PtySession {

    private final String worktreeId;
    private final PtyProcess process;
    private final OutputBuffer output = new OutputBuffer();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    PtySession(String worktreeId, Path workingDirectory, String[] command, Map<String, String> environment) {
        this.worktreeId = worktreeId;
        try {
            this.process = new PtyProcessBuilder()
                    .setCommand(command)
                    .setDirectory(workingDirectory.toString())
                    .setEnvironment(environment)
                    .start();
        } catch (IOException e) {
            throw new PtySessionStartException(worktreeId, e);
        }
        Thread drainThread = new Thread(this::drain, "pty-drain-" + worktreeId);
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
            }
        } catch (IOException ignored) {
            // The process ended or its pty closed; nothing more to drain.
        }
    }

    public String worktreeId() {
        return worktreeId;
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
            throw new PtySessionIoException(worktreeId, e);
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            process.destroy();
        }
    }
}
