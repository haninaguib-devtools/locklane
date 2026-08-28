package dev.locklane.engine.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injectable publisher other engine components call to push a small JSON notification
 * to every browser connected to {@code /ws/events} (#128) — the app-wide channel,
 * distinct from the per-session terminal sockets at {@code /ws/sessions/*}. No producer
 * is wired to this yet; that is #129/#130.
 *
 * <p>{@link EventsWebSocketHandler} registers and unregisters sessions here as
 * connections come and go; this class only fans a message out to whatever is
 * currently registered.
 */
@Component
public class EventBroadcaster {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public EventBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void register(WebSocketSession session) {
        sessions.add(session);
    }

    void unregister(WebSocketSession session) {
        sessions.remove(session);
    }

    /**
     * How many sessions are currently registered. Package-private, for tests: this
     * registry is the only signal that genuinely reflects server-side registration
     * state — every client-observable callback, including the client's own
     * {@code afterConnectionClosed}, can fire before the server's application-level
     * callback has finished updating it (#167).
     */
    int registeredSessionCount() {
        return sessions.size();
    }

    /** Broadcasts {@code {"type": "<type>"}} with no further fields. */
    public void broadcast(String type) {
        broadcast(type, Map.of());
    }

    /**
     * Broadcasts {@code {"type": "<type>", ...fields}} to every connected client.
     * {@code fields} must not itself use the key {@code "type"}.
     */
    public void broadcast(String type, Map<String, ?> fields) {
        TextMessage payload = toMessage(type, fields);
        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }

    /**
     * Sends {@code {"type": "<type>", ...fields}} to a single session, whether or not it
     * is registered here (#273: the version stamp goes to a session before it is
     * registered, so a broadcast racing the handshake can never land ahead of it).
     */
    public void sendTo(WebSocketSession session, String type, Map<String, ?> fields) {
        send(session, toMessage(type, fields));
    }

    private TextMessage toMessage(String type, Map<String, ?> fields) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.putAll(fields);
        try {
            return new TextMessage(objectMapper.writeValueAsString(message));
        } catch (IOException e) {
            throw new IllegalArgumentException("Event of type '" + type + "' could not be serialized", e);
        }
    }

    private void send(WebSocketSession session, TextMessage payload) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            session.sendMessage(payload);
        } catch (IOException e) {
            // The connection is going away; close it so its own afterConnectionClosed
            // callback removes it from `sessions` the same way any other close does.
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // Already gone — nothing productive to do with this failure here.
            }
        }
    }
}
