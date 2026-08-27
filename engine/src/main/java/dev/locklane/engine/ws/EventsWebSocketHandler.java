package dev.locklane.engine.ws;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The app-wide events endpoint, {@code /ws/events} (#128): server-to-client only, so
 * this handler's only job is tracking which sessions are live for
 * {@link EventBroadcaster} to fan messages out to. Any inbound message is ignored —
 * there is no client-to-server protocol on this channel.
 */
public class EventsWebSocketHandler extends TextWebSocketHandler {

    private final EventBroadcaster broadcaster;

    public EventsWebSocketHandler(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.register(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }
}
