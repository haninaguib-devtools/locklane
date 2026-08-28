package dev.locklane.engine.ws;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * The app-wide events endpoint, {@code /ws/events} (#128): server-to-client only, so
 * this handler's only job is tracking which sessions are live for
 * {@link EventBroadcaster} to fan messages out to. Any inbound message is ignored —
 * there is no client-to-server protocol on this channel.
 *
 * <p>Every connection is greeted with an {@code engineVersion} message before it is
 * registered (#273): a stale client's service worker never checks for updates on its
 * own, so this is what lets a reconnect after an engine restart tell the client its
 * cached bundle may be out of date.
 */
public class EventsWebSocketHandler extends TextWebSocketHandler {

    private final EventBroadcaster broadcaster;
    private final String versionStamp;

    public EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp) {
        this.broadcaster = broadcaster;
        this.versionStamp = versionStamp;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.sendTo(session, "engineVersion", Map.of("version", versionStamp));
        broadcaster.register(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }
}
