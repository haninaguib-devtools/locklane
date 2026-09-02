package dev.locklane.engine.ws;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which attached browser client's terminal size a session's PTY follows
 * (#574). A session can have several clients attached at once — a second browser
 * window, a second tab, a preview — and each reports its own size. Until #574 the
 * PTY simply took whichever resize arrived last, so a background window a few
 * columns narrower or wider silently re-wrapped the prompt box in the window the
 * user was actually typing in.
 *
 * <p>The rule: a resize from the attachment that most recently reported focus
 * ({@code '2'} frame, #130) is applied; a resize from any other attachment is only
 * remembered, and applied the moment that attachment reports focus. While no
 * attachment has reported focus (a single tab that mounted inactive, or the focused
 * one has since detached), every resize applies, exactly as before — so a lone client
 * never has to fight anyone for its size.
 *
 * <p>Keyed by the terminal session id and the WebSocket session id, which is all the
 * handler knows an attachment by; nothing here touches the PTY, the caller applies
 * whatever size this returns.
 */
class AttachmentSizeArbiter {

    /** A terminal size in character cells. */
    record Size(int columns, int rows) {
    }

    private final Map<String, Map<String, Size>> sizesByTerminal = new HashMap<>();
    private final Map<String, String> focusedByTerminal = new HashMap<>();

    /**
     * An attachment reported a new size. Returns the size to apply to the PTY, or
     * empty when this attachment is not the focused one and must wait its turn.
     */
    synchronized Optional<Size> resized(String terminalId, String attachmentId, Size size) {
        sizesByTerminal.computeIfAbsent(terminalId, id -> new HashMap<>()).put(attachmentId, size);
        String focused = focusedByTerminal.get(terminalId);
        if (focused == null || focused.equals(attachmentId)) {
            return Optional.of(size);
        }
        return Optional.empty();
    }

    /**
     * An attachment reported focus: it becomes the one the PTY follows. Returns its
     * last reported size, if it has one, so the caller can apply it right away.
     */
    synchronized Optional<Size> focused(String terminalId, String attachmentId) {
        focusedByTerminal.put(terminalId, attachmentId);
        Map<String, Size> sizes = sizesByTerminal.get(terminalId);
        return sizes == null ? Optional.empty() : Optional.ofNullable(sizes.get(attachmentId));
    }

    /**
     * An attachment went away. Its size is forgotten, and if it was the focused one
     * nobody is focused until the next focus frame — so the remaining attachment's
     * next resize applies rather than being held for a client that no longer exists.
     */
    synchronized void detached(String terminalId, String attachmentId) {
        Map<String, Size> sizes = sizesByTerminal.get(terminalId);
        if (sizes != null) {
            sizes.remove(attachmentId);
            if (sizes.isEmpty()) {
                sizesByTerminal.remove(terminalId);
            }
        }
        if (attachmentId.equals(focusedByTerminal.get(terminalId))) {
            focusedByTerminal.remove(terminalId);
        }
    }
}
