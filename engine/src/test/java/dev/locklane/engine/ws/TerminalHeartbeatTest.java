package dev.locklane.engine.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers #279's keepalive mechanism in isolation, with a fake session and a
 * controllable clock rather than a real PTY attach: a live session is pinged on
 * each tick and stays open as long as it keeps ponging, but one that stops
 * answering is closed once {@link TerminalHeartbeat#MISSED_PONGS_BEFORE_CLOSE}
 * intervals have passed with no pong — the mechanism that finally gives a stale
 * client connection a real {@code close} event to react to.
 */
class TerminalHeartbeatTest {

    private static final long INTERVAL_MS = 1000;

    @Test
    void aLiveSessionIsPingedOnEachTick() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        TerminalHeartbeat heartbeat = new TerminalHeartbeat(clock, INTERVAL_MS);
        WebSocketSession session = fakeSession("a");
        heartbeat.track(session);

        heartbeat.tick();

        verify(session).sendMessage(any(PingMessage.class));
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void repeatedPongsKeepASessionAliveIndefinitely() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        TerminalHeartbeat heartbeat = new TerminalHeartbeat(clock, INTERVAL_MS);
        WebSocketSession session = fakeSession("a");
        heartbeat.track(session);

        for (int i = 0; i < 5; i++) {
            clock.advance(INTERVAL_MS);
            heartbeat.tick();
            heartbeat.recordPong(session);
        }

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void aSessionThatStopsPongingIsClosedAfterTwoMissedIntervals() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        TerminalHeartbeat heartbeat = new TerminalHeartbeat(clock, INTERVAL_MS);
        WebSocketSession session = fakeSession("a");
        heartbeat.track(session);

        clock.advance(INTERVAL_MS);
        heartbeat.tick(); // one interval with no pong -- not yet stale
        verify(session, never()).close(any(CloseStatus.class));

        clock.advance(INTERVAL_MS);
        heartbeat.tick(); // two intervals with no pong -- stale

        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void anUntrackedSessionIsNeverPingedOrClosed() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        TerminalHeartbeat heartbeat = new TerminalHeartbeat(clock, INTERVAL_MS);
        WebSocketSession session = fakeSession("a");
        heartbeat.track(session);
        heartbeat.untrack(session);

        clock.advance(INTERVAL_MS * 10);
        heartbeat.tick();

        verify(session, never()).sendMessage(any());
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void aLatePongForAnAlreadyUntrackedSessionDoesNotResurrectIt() throws Exception {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        TerminalHeartbeat heartbeat = new TerminalHeartbeat(clock, INTERVAL_MS);
        WebSocketSession session = fakeSession("a");
        heartbeat.track(session);
        heartbeat.untrack(session);

        heartbeat.recordPong(session);
        clock.advance(INTERVAL_MS * 10);
        heartbeat.tick();

        verify(session, never()).sendMessage(any());
        verify(session, never()).close(any(CloseStatus.class));
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
