package dev.locklane.engine.pty;

/** The pseudo-terminal process for a worktree failed to start. */
public class PtySessionStartException extends RuntimeException {

    public PtySessionStartException(String worktreeId, Throwable cause) {
        super("Failed to start PTY session for worktree '" + worktreeId + "'", cause);
    }
}
