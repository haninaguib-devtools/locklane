package dev.locklane.engine.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers #665's keepalive on {@code /ws/events} itself -- the wiring between
 * {@link EventsWebSocketHandler} and {@link TerminalHeartbeat}, which {@link
 * TerminalHeartbeatTest} already covers in isolation with a fake session and a
 * controllable {@link Clock}, the same way this test drives it here.
 *
 * <p>Also covers the application-level {@code {"type":"heartbeat"}} message each tick
 * broadcasts (#762): it goes to every connection still live after the tick, through the
 * same registered wrapper the ping went through, and one connection's failing write is
 * contained the same way any broadcast's is (#761).
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
    void aConnectionWhosePingThrowsIsClosedWhileTheOthersAreStillPinged() throws Exception {
        // #761: the events ticker's containment — one connection's failing write must
        // not abort the tick for the rest.
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventsWebSocketHandler handler = handler(clock);
        WebSocketSession first = fakeSession("a");
        WebSocketSession broken = fakeSession("b");
        WebSocketSession third = fakeSession("c");
        doThrow(new IllegalStateException("The remote endpoint was in state [TEXT_PARTIAL_WRITING]"))
                .when(broken).sendMessage(any());
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(broken);
        handler.afterConnectionEstablished(third);

        handler.sendHeartbeats();

        verify(first).sendMessage(any(PingMessage.class));
        verify(third).sendMessage(any(PingMessage.class));
        verify(broken).close(any(CloseStatus.class));
        verify(first, never()).close(any(CloseStatus.class));
        verify(third, never()).close(any(CloseStatus.class));
    }

    @Test
    void eachTickBroadcastsAHeartbeatMessageThroughTheBroadcaster() throws Exception {
        // #762: the text message is what the browser can actually see -- it is never told
        // about the protocol ping -- and it goes out via the broadcaster so it reaches
        // exactly the registered (serialized, #761) sessions.
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = handler(clock, broadcaster);
        handler.afterConnectionEstablished(fakeSession("a"));

        handler.sendHeartbeats();

        verify(broadcaster).broadcast(EventsWebSocketHandler.HEARTBEAT_TYPE);
    }

    @Test
    void theHeartbeatMessageReachesEveryLiveConnectionAndOneFailingWriteDoesNotStopTheRest()
            throws Exception {
        // #762 through a real broadcaster: every live session gets both the ping and the
        // {"type":"heartbeat"} text on one tick, and a session whose text write throws
        // is closed while the others still receive theirs (#761's containment).
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventsWebSocketHandler handler = handler(clock, new EventBroadcaster(new ObjectMapper()));
        WebSocketSession first = openSession("a");
        WebSocketSession broken = openSession("b");
        WebSocketSession third = openSession("c");
        doThrow(new IllegalStateException("The remote endpoint was in state [TEXT_PARTIAL_WRITING]"))
                .when(broken).sendMessage(argThat(heartbeatMessage()));
        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(broken);
        handler.afterConnectionEstablished(third);

        handler.sendHeartbeats();

        verify(first).sendMessage(any(PingMessage.class));
        verify(first).sendMessage(argThat(heartbeatMessage()));
        verify(third).sendMessage(any(PingMessage.class));
        verify(third).sendMessage(argThat(heartbeatMessage()));
        verify(broken).close(any(CloseStatus.class));
        verify(first, never()).close(any(CloseStatus.class));
        verify(third, never()).close(any(CloseStatus.class));
    }

    @Test
    void aConnectionTheTickJustClosedForMissingPongsIsNotSentAHeartbeatMessage() throws Exception {
        // #762: the tick runs first, so a connection it closes as stale is not written to
        // again on the same tick.
        MutableClock clock = new MutableClock(Instant.EPOCH);
        EventsWebSocketHandler handler = handler(clock, new EventBroadcaster(new ObjectMapper()));
        WebSocketSession session = openSession("a");
        handler.afterConnectionEstablished(session);

        clock.advance(INTERVAL_MS * TerminalHeartbeat.MISSED_PONGS_BEFORE_CLOSE);
        handler.sendHeartbeats();

        verify(session).close(any(CloseStatus.class));
        verify(session, never()).sendMessage(argThat(heartbeatMessage()));
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
        return handler(clock, mock(EventBroadcaster.class));
    }

    private static EventsWebSocketHandler handler(Clock clock, EventBroadcaster broadcaster) {
        return new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0-SNAPSHOT", Optional::empty, clock,
                INTERVAL_MS);
    }

    private static WebSocketSession fakeSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    /**
     * A fake that reports open until it is closed, the way a real session does -- the
     * broadcaster skips a session that is no longer open rather than writing to it.
     */
    private static WebSocketSession openSession(String id) throws Exception {
        WebSocketSession session = fakeSession(id);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            when(session.isOpen()).thenReturn(false);
            return null;
        }).when(session).close(any(CloseStatus.class));
        return session;
    }

    private static ArgumentMatcher<WebSocketMessage<?>> heartbeatMessage() {
        return message -> message instanceof TextMessage text
                && text.getPayload().equals("{\"type\":\"heartbeat\"}");
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
