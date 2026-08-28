package dev.locklane.engine.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers #287's "a newly-connecting client is told too" done-when directly, against a
 * fake supplier — no real gh process, no scheduled check, no Spring context. #273's
 * engineVersion greeting is covered by {@link EventsWebSocketHandlerIntegrationTest}.
 */
class EventsWebSocketHandlerTest {

    @Test
    void aConnectionIsToldAboutAnAlreadyKnownNewerReleaseRightAway() {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", () -> Optional.of("0.2.0"));
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(session, "releaseAvailable", Map.of("version", "0.2.0"));
    }

    @Test
    void aConnectionIsToldNothingWhenNoNewerReleaseIsKnownYet() {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionEstablished(session);

        verify(broadcaster, never()).sendTo(eq(session), eq("releaseAvailable"), anyMap());
    }
}
