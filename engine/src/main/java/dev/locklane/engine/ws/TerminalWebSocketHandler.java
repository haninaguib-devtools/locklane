package dev.locklane.engine.ws;

import dev.locklane.engine.pty.PtySession;
import dev.locklane.engine.pty.SessionRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attaches a browser client to a worktree's {@link PtySession} over WebSocket:
 * {@code /ws/sessions/{worktreeId}[?dir=<path>]}. {@code dir} is required only the
 * first time a worktree is seen; after that its working directory is already known
 * (in-memory if the session is still live, or from SQLite via
 * {@link SessionRegistry#lastKnownWorkingDirectory} after a restart).
 *
 * <p>Closing a connection never kills the underlying session (#7's done-when) — only
 * this connection's subscription is torn down, so the session keeps running and
 * producing output for the next client to reattach and replay.
 */
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final SessionRegistry sessionRegistry;
    private final Map<String, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        String worktreeId = worktreeId(wsSession);
        Path workingDirectory = resolveWorkingDirectory(wsSession, worktreeId);
        if (workingDirectory == null) {
            wsSession.close(CloseStatus.BAD_DATA.withReason(
                    "Unknown worktree '" + worktreeId + "': pass ?dir=<path> to start one"));
            return;
        }

        PtySession session = sessionRegistry.attach(worktreeId, workingDirectory);

        // Replay everything produced so far before subscribing, so nothing produced
        // between the snapshot and the subscription taking effect is lost or
        // duplicated — subscribe() only ever delivers output from this point on.
        wsSession.sendMessage(new TextMessage(session.bufferedOutput()));
        AutoCloseable subscription = session.subscribe(chunk -> forward(wsSession, chunk));
        subscriptions.put(wsSession.getId(), subscription);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        String worktreeId = worktreeId(wsSession);
        sessionRegistry.find(worktreeId).ifPresent(session -> session.write(message.getPayload()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) throws Exception {
        AutoCloseable subscription = subscriptions.remove(wsSession.getId());
        if (subscription != null) {
            subscription.close();
        }
        // No call into SessionRegistry/PtySession here, deliberately: this connection
        // closing must never stop the session itself.
    }

    private static void forward(WebSocketSession wsSession, byte[] chunk) {
        if (!wsSession.isOpen()) {
            return;
        }
        try {
            wsSession.sendMessage(new TextMessage(new String(chunk, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            // The connection is going away; afterConnectionClosed will clean up the
            // subscription shortly. Nothing productive to do with this failure here.
        }
    }

    private static String worktreeId(WebSocketSession wsSession) {
        String path = wsSession.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private Path resolveWorkingDirectory(WebSocketSession wsSession, String worktreeId) {
        String dirParam = queryParam(wsSession, "dir");
        if (dirParam != null) {
            return Path.of(dirParam);
        }
        return sessionRegistry.lastKnownWorkingDirectory(worktreeId).orElse(null);
    }

    private static String queryParam(WebSocketSession wsSession, String name) {
        String query = wsSession.getUri().getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (param.substring(0, eq).equals(name)) {
                return URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
