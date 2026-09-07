package dev.locklane.engine.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a stale/half-open {@code /ws/sessions/*} connection within a bounded time
 * (#279): neither side of that connection has any other way to notice a silently
 * dropped socket — a backgrounded tab throttled by the browser, or a network device
 * closing an idle connection without ever sending a close frame — so this pings
 * every live session on a fixed schedule and closes any that misses too many pongs
 * in a row, which finally gives the client a real {@code close} event to react to.
 * A browser answers a server-sent {@link PingMessage} with a pong automatically, at
 * the WebSocket protocol level; no client-side code is involved on that side.
 *
 * <p>Owns its own tracking of which sessions are live and when each last answered,
 * independent of {@link TerminalWebSocketHandler}'s subscription bookkeeping, so it
 * can be exercised directly with a fake session and a controllable {@link Clock}
 * rather than through a full PTY attach.
 *
 * <p>Also home to {@link #serialized}, the one-lock wrapper every write to a live
 * connection goes through (#761): this heartbeat is the writer both endpoints' sessions
 * have in common, so the wrapper that keeps its pings from colliding with a broadcast
 * or a PTY drain thread's output lives alongside it.
 */
class TerminalHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TerminalHeartbeat.class);

    // One missed pong could just be a slow tick under load; two in a row is treated
    // as the connection actually being gone.
    static final int MISSED_PONGS_BEFORE_CLOSE = 2;

    /**
     * How long one write may block before the connection is judged stuck (#761). A
     * client that stops reading — a suspended laptop whose socket has not died yet —
     * would otherwise hold the writer, and with it the lock every other writer to that
     * connection waits on, indefinitely.
     */
    static final int SEND_TIME_LIMIT_MS = 10_000;

    /**
     * How much output may queue up behind a slow write before the connection is judged
     * stuck (#761). Sized for a terminal's output bursts, which are far larger than
     * anything on the events channel.
     */
    static final int SEND_BUFFER_LIMIT_BYTES = 1024 * 1024;

    /**
     * Wraps a freshly-established connection so that every write to it — a broadcast
     * from whichever thread produced the event, a PTY drain thread's output, this
     * heartbeat's own ping — goes through one lock (#761). Tomcat's session is not safe
     * for concurrent sends: two threads writing at once throws
     * {@code IllegalStateException} ("The remote endpoint was in state
     * [TEXT_PARTIAL_WRITING]"), which is exactly the collision seen in production
     * between a scheduled broadcast and a heartbeat ping. Spring's decorator lets one
     * writer through at a time and queues the rest; past {@link #SEND_TIME_LIMIT_MS} or
     * {@link #SEND_BUFFER_LIMIT_BYTES} it gives up on that one connection with a
     * {@code SessionLimitExceededException} instead of holding everyone else's writes
     * hostage to a client that stopped reading.
     *
     * <p>The handlers register the wrapper — never the raw session — with every writer,
     * and each writer keys its bookkeeping by {@link WebSocketSession#getId()}, which the
     * wrapper delegates, so the raw session Spring hands back to
     * {@code afterConnectionClosed} still finds the entry the wrapper was registered
     * under.
     */
    static WebSocketSession serialized(WebSocketSession connection) {
        return new ConcurrentWebSocketSessionDecorator(connection, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
    }

    private final Clock clock;
    private final long intervalMs;
    private final Map<String, WebSocketSession> liveSessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastPongAt = new ConcurrentHashMap<>();

    TerminalHeartbeat(Clock clock, long intervalMs) {
        this.clock = clock;
        this.intervalMs = intervalMs;
    }

    /** Starts tracking a newly-attached connection. */
    void track(WebSocketSession wsSession) {
        liveSessions.put(wsSession.getId(), wsSession);
        lastPongAt.put(wsSession.getId(), clock.instant());
    }

    /** Stops tracking a connection that closed on its own, by any means. */
    void untrack(WebSocketSession wsSession) {
        liveSessions.remove(wsSession.getId());
        lastPongAt.remove(wsSession.getId());
    }

    /** Records a pong just received from a tracked connection; a no-op if it was already untracked. */
    void recordPong(WebSocketSession wsSession) {
        lastPongAt.replace(wsSession.getId(), clock.instant());
    }

    /** One heartbeat cycle: ping every live session, closing any overdue by {@link #MISSED_PONGS_BEFORE_CLOSE} intervals. */
    void tick() {
        Instant now = clock.instant();
        long staleAfterMs = intervalMs * MISSED_PONGS_BEFORE_CLOSE;
        for (WebSocketSession wsSession : liveSessions.values()) {
            Instant lastPong = lastPongAt.getOrDefault(wsSession.getId(), now);
            if (Duration.between(lastPong, now).toMillis() >= staleAfterMs) {
                closeStale(wsSession);
                continue;
            }
            try {
                wsSession.sendMessage(new PingMessage());
            } catch (IOException | RuntimeException e) {
                // Contained per session (#761): one connection failing its ping — the
                // socket already gone, or a write its serializing wrapper refused — must
                // not abort this tick for every session after it in the set.
                log.debug("Ping failed for session {}; closing", wsSession.getId(), e);
                closeStale(wsSession);
            }
        }
    }

    private void closeStale(WebSocketSession wsSession) {
        // Untracked up front: afterConnectionClosed will call untrack() again once
        // the close below actually completes, and a second removal is a no-op --
        // this just keeps the same session from being closed twice by an overlapping
        // tick if close() itself is slow.
        untrack(wsSession);
        try {
            wsSession.close(CloseStatus.SESSION_NOT_RELIABLE.withReason("No pong received"));
        } catch (IOException | RuntimeException e) {
            // silent: already going away; nothing productive to do with this failure
            // here.
        }
    }
}
