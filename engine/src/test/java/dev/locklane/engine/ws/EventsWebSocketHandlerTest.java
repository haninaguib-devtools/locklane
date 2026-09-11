package dev.locklane.engine.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.github.ReleaseUpdateChecker;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.pty.PtySession;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 *
 * <p>The greeting's {@code heartbeatIntervalMs} (#762) is pinned here too: it is how the
 * client learns the interval its own liveness check counts against, so it must be the
 * configured value, never a number the two sides each hardcode.
 *
 * <p>Also covers #790's connect-time catch-up: a new connection is sent exactly one
 * {@code consoleAttention} {@code waiting} message per live session currently waiting,
 * none for an active one, in the live broadcast's own shape, after the greeting and
 * only once the connection is registered for broadcasts. #854's {@code reason} field
 * rides along on the same snapshot line.
 */
class EventsWebSocketHandlerTest {

    @Test
    void aConnectingClientIsSentOneWaitingEventPerWaitingSessionAndNoneForAnActiveOne(@TempDir Path dbDir,
            @TempDir Path workDir) {
        // #790, end to end through the real registry: two sessions ring the bell, one
        // does not, and the handler is wired to the registry the way Spring wires it.
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        PtySession waiting = registry.attach("42-7-waiting", workDir);
        PtySession alsoWaiting = registry.attach("42-8-also-waiting", workDir);
        PtySession active = registry.attach("42-9-active", workDir);
        waiting.write("printf '\\a'\n");
        alsoWaiting.write("printf '\\a'\n");
        waitUntil(() -> waiting.attentionState() == PtySession.AttentionState.WAITING
                && alsoWaiting.attentionState() == PtySession.AttentionState.WAITING, Duration.ofSeconds(5));
        assertThat(active.attentionState()).isEqualTo(PtySession.AttentionState.ACTIVE);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0",
                Optional::empty, registry::waitingSessions);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("consoleAttention"),
                eq(Map.of("sessionId", "42-7-waiting", "state", "waiting", "reason", "bell")));
        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("consoleAttention"),
                eq(Map.of("sessionId", "42-8-also-waiting", "state", "waiting", "reason", "bell")));
        // Exactly one per waiting session: nothing for the active one, no duplicates,
        // and never a "state: active" line -- the snapshot only ever says "waiting".
        verify(broadcaster, times(2)).sendTo(any(), eq("consoleAttention"), anyMap());
        verify(broadcaster, never()).sendTo(any(), eq("consoleAttention"),
                argThat(fields -> "42-9-active".equals(fields.get("sessionId"))));
        verify(broadcaster, never()).sendTo(any(), eq("consoleAttention"),
                argThat(fields -> "active".equals(fields.get("state"))));
    }

    @Test
    void theSnapshotIsSentAfterTheGreetingAndOnlyOnceTheConnectionIsRegistered() {
        // #790's ordering contract: engineVersion first, so a client's staleness check
        // never waits behind the snapshot; register before the snapshot is read, so a
        // state change racing the connect is delivered as a broadcast rather than lost.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        List<String> snapshotReadAfterRegister = new ArrayList<>();
        AtomicBoolean registered = new AtomicBoolean(false);
        doAnswer(invocation -> {
            registered.set(true);
            return null;
        }).when(broadcaster).register(any());
        Supplier<Collection<SessionRegistry.WaitingSession>> waitingSessions = () -> {
            snapshotReadAfterRegister.add(registered.get() ? "after" : "before");
            return List.of(new SessionRegistry.WaitingSession("42-7-slug", PtySession.WaitingReason.QUIET, null));
        };
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0",
                Optional::empty, waitingSessions);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        assertThat(snapshotReadAfterRegister).containsExactly("after");
        InOrder inOrder = inOrder(broadcaster);
        inOrder.verify(broadcaster).sendTo(any(), eq("engineVersion"), anyMap());
        inOrder.verify(broadcaster).register(argThat(serializedWrapperAround(session)));
        inOrder.verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("consoleAttention"),
                eq(Map.of("sessionId", "42-7-slug", "state", "waiting", "reason", "quiet")));
    }

    @Test
    void aConnectingClientIsSentNoAttentionEventWhenNoSessionIsWaiting() {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0",
                Optional::empty, List::of);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster, never()).sendTo(any(), eq("consoleAttention"), anyMap());
    }

    @Test
    void theGreetingCarriesTheBuildStampAndTheRunningVersion() {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0-SNAPSHOT", "o/r", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("engineVersion"),
                eq(Map.of("version", "stamp", "release", "0.1.0-SNAPSHOT", "heartbeatIntervalMs", 20_000L)));
    }

    @Test
    void theGreetingCarriesTheConfiguredHeartbeatInterval() {
        // #762: the client arms its own liveness check from this value, so it must be
        // the interval this handler actually ticks on, not a constant.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", "o/r",
                Optional::empty, Clock.systemUTC(), 1234L);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("engineVersion"),
                eq(Map.of("version", "stamp", "release", "0.1.0", "heartbeatIntervalMs", 1234L,
                        "releaseUrl", "https://github.com/o/r/releases/tag/v0.1.0")));
    }

    @Test
    void theGreetingCarriesTheRunningVersionsOwnReleaseUrlWhenItIsNotASnapshot() {
        // #799: the running build's own Releases-page link, assembled from the
        // configured repository and the release version — never fetched over the
        // network, unlike ReleaseUpdateChecker's newer-release lookup.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.2.20", "o/r", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("engineVersion"),
                eq(Map.of("version", "stamp", "release", "0.2.20", "heartbeatIntervalMs", 20_000L,
                        "releaseUrl", "https://github.com/o/r/releases/tag/v0.2.20")));
    }

    @Test
    void theGreetingOmitsReleaseUrlForASnapshotBuild() {
        // A -SNAPSHOT build was never tagged, so it has no release page to link to.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler =
                new EventsWebSocketHandler(broadcaster, "stamp", "0.2.20-SNAPSHOT", "o/r", Optional::empty);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s");

        handler.afterConnectionEstablished(session);

        verify(broadcaster).sendTo(argThat(serializedWrapperAround(session)), eq("engineVersion"),
                eq(Map.of("version", "stamp", "release", "0.2.20-SNAPSHOT", "heartbeatIntervalMs", 20_000L)));
    }

    @Test
    void aConnectionIsToldAboutAnAlreadyKnownNewerReleaseRightAway() {
        // The late-joiner replay (#287) carries the same version-plus-url payload the
        // broadcast does (#466), so a client connecting after detection sees the
        // identical banner, link included.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        EventsWebSocketHandler handler = new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", "o/r",
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
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", "o/r", Optional::empty);
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
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", "o/r", Optional::empty);
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
                new EventsWebSocketHandler(broadcaster, "stamp", "0.1.0", "o/r", Optional::empty);
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

    private static void waitUntil(Supplier<Boolean> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }
}
