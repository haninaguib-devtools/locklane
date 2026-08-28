package dev.locklane.engine.ws;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

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
 */
class TerminalHeartbeat {

    // One missed pong could just be a slow tick under load; two in a row is treated
    // as the connection actually being gone.
    static final int MISSED_PONGS_BEFORE_CLOSE = 2;

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
            } catch (IOException e) {
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
        } catch (IOException e) {
            // Already going away; nothing productive to do with this failure here.
        }
    }
}
