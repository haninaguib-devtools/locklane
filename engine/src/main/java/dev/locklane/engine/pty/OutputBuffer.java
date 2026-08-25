package dev.locklane.engine.pty;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Everything a session has produced so far, from the moment it started — kept
 * regardless of whether any client is currently reading, so a reattaching client
 * sees output produced while it was gone.
 */
final class OutputBuffer {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    synchronized void append(byte[] chunk, int length) {
        buffer.write(chunk, 0, length);
    }

    synchronized String snapshot() {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
