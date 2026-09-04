package dev.locklane.engine.ws;

import dev.locklane.engine.github.ReleaseUpdateChecker;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers #287's "a newly-connecting client is told too" done-when directly, against a
 * fake supplier — no real gh process, no scheduled check, no Spring context. #273's
 * engineVersion greeting is covered by {@link EventsWebSocketHandlerIntegrationTest};
 * the greeting's payload shape (#467) is pinned here where the inputs are fakes.
 */
class EventsWebSocketHandlerTest {

    @Test
    void theGreetingCarriesTheBuildStampAndTheRunningVersion() {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0-SNAPSHOT", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(session, "engineVersion",
                Map.of("version", "stamp", "release", "0.1.0-SNAPSHOT"));
    }

    @Test
    void aConnectionIsToldAboutAnAlreadyKnownNewerReleaseRightAway() {
        // The late-joiner replay (#287) carries the same version-plus-url payload the
        // broadcast does (#466), so a client connecting after detection sees the
        // identical banner, link included.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0",
                () -> Optional.of(new ReleaseUpdateChecker.NewerRelease(
                        "0.2.0", "https://github.com/o/r/releases/tag/v0.2.0")));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(session, "releaseAvailable",
                Map.of("version", "0.2.0", "url", "https://github.com/o/r/releases/tag/v0.2.0"));
    }

    @Test
    void aConnectionIsToldNothingWhenNoNewerReleaseIsKnownYet() {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster, never()).sendTo(eq(session), eq("releaseAvailable"), anyMap());
    }
}
