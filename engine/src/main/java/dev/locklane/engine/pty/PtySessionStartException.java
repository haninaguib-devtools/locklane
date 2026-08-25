package dev.locklane.engine.pty;

/** The pseudo-terminal process for a session failed to start. */
public class PtySessionStartException extends RuntimeException {

    public PtySessionStartException(String sessionId, Throwable cause) {
        super("Failed to start PTY session '" + sessionId + "'", cause);
    }
}
