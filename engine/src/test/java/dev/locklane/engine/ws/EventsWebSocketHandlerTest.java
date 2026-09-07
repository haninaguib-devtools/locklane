package dev.locklane.engine.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.github.ReleaseUpdateChecker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers #287's "a newly-connecting client is told too" done-when directly, against a
 * fake supplier — no real gh process, no scheduled check, no Spring context. #273's
 * engineVersion greeting is covered by {@link EventsWebSocketHandlerIntegrationTest};
 * the greeting's payload shape (#467) is pinned here where the inputs are fakes.
 *
 * <p>Also covers #761's registration contract: what the handler hands every writer is
 * the serializing wrapper around the connection, never the raw session, so concurrent
 * broadcasts from several threads reach one socket strictly one at a time.
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

        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("engineVersion"),
                eq(Map.of("version", "stamp", "release", "0.1.0-SNAPSHOT")));
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

        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("releaseAvailable"),
                eq(Map.of("version", "0.2.0", "url", "https://github.com/o/r/releases/tag/v0.2.0")));
    }

    @Test
    void aConnectionIsToldNothingWhenNoNewerReleaseIsKnownYet() {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster, never()).sendTo(any(), eq("releaseAvailable"), anyMap());
    }

    @Test
    void theRegisteredSessionIsTheSerializingWrapperAroundTheConnection() {
        // #761: the broadcaster must never see the raw session — every writer, the
        // greeting included, goes through the one wrapper so no write bypasses its lock.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).register(argThat(serializedWrapperAround(session)));
        verify(broadcaster, never()).register(session);
        verify(broadcaster, never()).sendTo(eq(session), any(), anyMap());
    }

    @Test
    void concurrentBroadcastsReachARegisteredConnectionOneAtATime() throws Exception {
        // #761's production failure, in miniature: several threads broadcasting at once
        // against one connection whose socket asserts it is never written to by two
        // threads simultaneously — the condition under which Tomcat throws
        // IllegalStateException ("TEXT_PARTIAL_WRITING"). Registered through the handler,
        // so this proves the handler's own registration is what serializes the writes.
        EventBroadcaster broadcaster = new EventBroadcaster(new ObjectMapper());
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", Optional::empty);
        AtomicBoolean writing = new AtomicBoolean(false);
        AtomicInteger overlaps = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            if (!writing.compareAndSet(false, true)) {
                overlaps.incrementAndGet();
            }
            try {
                // Hold the socket briefly so a second writer has a real window to collide.
                LockSupport.parkNanos(50_000);
                delivered.incrementAndGet();
            } finally {
                writing.set(false);
            }
            return null;
        }).when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);

        int threads = 4;
        int broadcastsPerThread = 50;
        List<Throwable> failures = new ArrayList<>();
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread writer = new Thread(() -> {
                for (int i = 0; i < broadcastsPerThread; i++) {
                    broadcaster.broadcast("issuesChanged");
                }
            });
            writer.setUncaughtExceptionHandler((thread, error) -> {
                synchronized (failures) {
                    failures.add(error);
                }
            });
            writers.add(writer);
        }
        writers.forEach(Thread::start);
        for (Thread writer : writers) {
            writer.join(30_000);
        }
        // A message queued behind another thread's write is flushed by the next write
        // through the wrapper; one final broadcast from this thread drains any straggler
        // so the count below is exact.
        broadcaster.broadcast("issuesChanged");

        assertThat(failures).isEmpty();
        assertThat(overlaps).hasValue(0);
        // The greeting, every thread's broadcasts, and the final flush.
        assertThat(delivered).hasValue(1 + threads * broadcastsPerThread + 1);
        assertThat(broadcaster.registeredSessionCount()).isEqualTo(1);
    }

    private static ArgumentMatcher<WebSocketSession> serializedWrapperAround(WebSocketSession raw) {
        return candidate -> candidate instanceof ConcurrentWebSocketSessionDecorator
                && WebSocketSessionDecorator.unwrap(candidate) == raw;
    }
}
