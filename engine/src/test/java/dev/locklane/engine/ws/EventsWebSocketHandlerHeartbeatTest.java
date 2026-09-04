package dev.locklane.engine.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers #665's keepalive on {@code /ws/events} itself -- the wiring between
 * {@link EventsWebSocketHandler} and {@link TerminalHeartbeat}, which {@link
 * TerminalHeartbeatTest} already covers in isolation with a fake session and a
 * controllable {@link Clock}, the same way this test drives it here.
 */
class EventsWebSocketHandlerHeartbeatTest {

    private static final long INTERVAL_MS = 1000;

    @Test
    void aLiveConnectionIsPingedOnEachTick() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventsWebSocketHandler handler = handler(clock);
        WebSocketSession session = fakeSession("a");

        handler.afterConnectionEstablished(session);
        handler.sendHeartbeats();

        verify(session).sendMessage(any(PingMessage.class));
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void repeatedPongsKeepAConnectionAliveIndefinitely() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventsWebSocketHandler handler = handler(clock);
        WebSocketSession session = fakeSession("a");
        handler.afterConnectionEstablished(session);

        for (int i = 0; i < 5; i++) {
            clock.advance(INTERVAL_MS);
            handler.sendHeartbeats();
            handler.handlePongMessage(session, mock(PongMessage.class));
        }

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void aConnectionThatStopsAnsweringPongsIsClosedWithinTwoIntervals() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventsWebSocketHandler handler = handler(clock);
        WebSocketSession session = fakeSession("a");
        handler.afterConnectionEstablished(session);

        clock.advance(INTERVAL_MS);
        handler.sendHeartbeats(); // one interval with no pong -- not yet stale
        verify(session, never()).close(any(CloseStatus.class));

        clock.advance(INTERVAL_MS);
        handler.sendHeartbeats(); // two intervals with no pong -- stale

        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void aConnectionClosedNormallyIsNoLongerPinged() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventsWebSocketHandler handler = handler(clock);
        WebSocketSession session = fakeSession("a");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        clock.advance(INTERVAL_MS * 10);
        handler.sendHeartbeats();

        verify(session, never()).sendMessage(any(PingMessage.class));
        verify(session, never()).close(any(CloseStatus.class));
    }

    private static EventsWebSocketHandler handler(Clock clock) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        return new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0-SNAPSHOT", Optional::empty, clock,
                INTERVAL_MS);
    }

    private static WebSocketSession fakeSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(long millis) {
            now.updateAndGet(instant -> instant.plusMillis(millis));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
