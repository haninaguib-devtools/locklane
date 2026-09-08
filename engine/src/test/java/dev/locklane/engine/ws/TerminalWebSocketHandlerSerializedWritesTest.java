package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ProjectAgentSessionService;
import dev.locklane.engine.persistence.WorktreeSessionAuthorization;
import dev.locklane.engine.pty.PtySession;
import dev.locklane.engine.pty.PtySession.OutputListener;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers #761 on the terminal endpoint, where the production collision was actually
 * seen: the PTY drain thread forwarding output and the scheduler thread's heartbeat
 * ping write to the same connection. Attaches through the real
 * {@link TerminalWebSocketHandler#afterConnectionEstablished} against a mocked registry
 * and PTY, so what is under test is the handler's own registration of the serializing
 * wrapper, then drives both writers at once.
 */
class TerminalWebSocketHandlerSerializedWritesTest {

    @Test
    void ptyOutputAndHeartbeatPingsReachTheConnectionOneAtATime() throws Exception {
        Attachment attachment = attach("a");

        int threads = 3;
        int chunksPerThread = 40;
        List<Thread> writers = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread writer = new Thread(() -> {
                for (int i = 0; i < chunksPerThread; i++) {
                    attachment.listener.onOutput("x".getBytes(StandardCharsets.UTF_8));
                }
            });
            writer.setUncaughtExceptionHandler((thread, error) -> {
                synchronized (failures) {
                    failures.add(error);
                }
            });
            writers.add(writer);
        }
        Thread pinger = new Thread(() -> {
            for (int i = 0; i < chunksPerThread; i++) {
                attachment.handler.sendHeartbeats();
            }
        });
        pinger.setUncaughtExceptionHandler((thread, error) -> {
            synchronized (failures) {
                failures.add(error);
            }
        });
        writers.add(pinger);
        writers.forEach(Thread::start);
        for (Thread writer : writers) {
            writer.join(30_000);
        }
        // Drains anything a writer queued behind another's write (see the same note in
        // EventsWebSocketHandlerTest) so the count below is exact.
        attachment.listener.onOutput("x".getBytes(StandardCharsets.UTF_8));

        assertThat(failures).isEmpty();
        assertThat(attachment.socket().overlaps).hasValue(0);
        // The replay of buffered output, every chunk, every ping, and the final drain.
        assertThat(attachment.socket().delivered).hasValue(1 + threads * chunksPerThread + chunksPerThread + 1);
        verify(attachment.socket().session, never()).close(any(CloseStatus.class));
    }

    @Test
    void aConnectionWhoseWriteIsRefusedIsClosedWithoutTheExceptionReachingTheDrainThread() throws Exception {
        // A RuntimeException escaping forward() would kill PtySession's drain loop for
        // every client attached to the session; it must end at this one connection.
        Attachment attachment = attach("a");
        doThrow(new IllegalStateException("The remote endpoint was in state [TEXT_PARTIAL_WRITING]"))
                .when(attachment.socket().session).sendMessage(any(TextMessage.class));

        attachment.listener.onOutput("x".getBytes(StandardCharsets.UTF_8));

        verify(attachment.socket().session).close(any(CloseStatus.class));
    }

    @Test
    void aConnectionWhosePingThrowsIsClosedWhileTheOthersAreStillPinged() throws Exception {
        // #761: the terminal ticker's containment — one connection's failing ping must
        // not abort the tick for the rest.
        Attachment attachment = attach("a", "b", "c");
        doThrow(new IllegalStateException("The remote endpoint was in state [TEXT_PARTIAL_WRITING]"))
                .when(attachment.sockets.get(1).session).sendMessage(any(PingMessage.class));

        attachment.handler.sendHeartbeats();

        verify(attachment.sockets.get(0).session).sendMessage(any(PingMessage.class));
        verify(attachment.sockets.get(2).session).sendMessage(any(PingMessage.class));
        verify(attachment.sockets.get(1).session).close(any(CloseStatus.class));
        verify(attachment.sockets.get(0).session, never()).close(any(CloseStatus.class));
        verify(attachment.sockets.get(2).session, never()).close(any(CloseStatus.class));
    }

    /** One handler with one PTY session and one attached connection per id. */
    private static Attachment attach(String... connectionIds) throws Exception {
        PtySession pty = mock(PtySession.class);
        when(pty.bufferedOutput()).thenReturn("replay");
        List<OutputListener> listeners = new ArrayList<>();
        doAnswer(invocation -> {
            listeners.add(invocation.getArgument(0));
            return (AutoCloseable) () -> { };
        }).when(pty).subscribe(any());
        SessionRegistry registry = mock(SessionRegistry.class);
        when(registry.attach(eq("s1"), any(), any(), anyString(), any(), any(), any())).thenReturn(pty);
        WorktreeSessionAuthorization authorization = mock(WorktreeSessionAuthorization.class);
        when(authorization.isVisibleTo(eq("s1"), anyString())).thenReturn(true);
        TerminalWebSocketHandler handler = new TerminalWebSocketHandler(registry, mock(ProjectAgentSessionService.class),
                authorization, Clock.systemUTC(), 60_000L);

        List<SingleWriterSocket> sockets = new ArrayList<>();
        for (String id : connectionIds) {
            SingleWriterSocket socket = new SingleWriterSocket(id);
            handler.afterConnectionEstablished(socket.session);
            sockets.add(socket);
        }
        assertThat(listeners).hasSize(connectionIds.length);
        return new Attachment(handler, sockets, listeners.get(0));
    }

    private record Attachment(TerminalWebSocketHandler handler, List<SingleWriterSocket> sockets,
            OutputListener listener) {
        SingleWriterSocket socket() {
            return sockets.get(0);
        }
    }

    /**
     * A fake connection whose socket records every write and counts any two that
     * overlap — the condition under which a real Tomcat session throws.
     */
    private static final class SingleWriterSocket {
        final WebSocketSession session = mock(WebSocketSession.class);
        final AtomicInteger overlaps = new AtomicInteger();
        final AtomicInteger delivered = new AtomicInteger();
        private final AtomicBoolean writing = new AtomicBoolean(false);

        SingleWriterSocket(String id) throws Exception {
            Principal principal = mock(Principal.class);
            when(principal.getName()).thenReturn("owner");
            when(session.getId()).thenReturn(id);
            when(session.isOpen()).thenReturn(true);
            when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/sessions/s1?dir=/tmp"));
            when(session.getPrincipal()).thenReturn(principal);
            doAnswer(invocation -> {
                if (!writing.compareAndSet(false, true)) {
                    overlaps.incrementAndGet();
                }
                try {
                    LockSupport.parkNanos(50_000);
                    delivered.incrementAndGet();
                } finally {
                    writing.set(false);
                }
                return null;
            }).when(session).sendMessage(any());
        }
    }
}
