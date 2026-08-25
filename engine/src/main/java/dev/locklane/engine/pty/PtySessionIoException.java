package dev.locklane.engine.pty;

/** Writing input to a worktree's pseudo-terminal failed. */
public class PtySessionIoException extends RuntimeException {

    public PtySessionIoException(String worktreeId, Throwable cause) {
        super("I/O error writing to PTY session for worktree '" + worktreeId + "'", cause);
    }
}
