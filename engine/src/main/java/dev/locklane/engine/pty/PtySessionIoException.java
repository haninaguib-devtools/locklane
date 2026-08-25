package dev.locklane.engine.pty;

/** Writing input to a session's pseudo-terminal failed. */
public class PtySessionIoException extends RuntimeException {

    public PtySessionIoException(String sessionId, Throwable cause) {
        super("I/O error writing to PTY session '" + sessionId + "'", cause);
    }
}
