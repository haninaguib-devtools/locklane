package dev.locklane.engine.ws;

import dev.locklane.engine.github.ReleaseUpdateChecker;
import dev.locklane.engine.github.ReleaseUpdateChecker.NewerRelease;
import dev.locklane.engine.pty.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The app-wide events endpoint, {@code /ws/events} (#128): server-to-client only, so
 * this handler's only job is tracking which sessions are live for
 * {@link EventBroadcaster} to fan messages out to. Any inbound message is ignored —
 * there is no client-to-server protocol on this channel.
 *
 * <p>Every connection is greeted with an {@code engineVersion} message before it is
 * registered (#273): a stale client's service worker never checks for updates on its
 * own, so this is what lets a reconnect after an engine restart tell the client its
 * cached bundle may be out of date. The greeting carries two facts (#467): the
 * {@code version} stamp (build <em>time</em>, differing between any two builds, what
 * the staleness comparison above runs on) and {@code release}, the human-readable
 * version this build was made as ({@code BuildProperties#getVersion()}, e.g.
 * {@code 0.1.0-SNAPSHOT}), which the client simply displays.
 *
 * <p>A connection also learns, right away, whether a newer permanent GitHub release than
 * the one running is already known about (#287) — {@code newerRelease} is a supplier
 * rather than a fixed value because that state can flip at any point during the engine's
 * lifetime, unlike the version stamp above, which is fixed at build time. A connection
 * made after the one-time {@code releaseAvailable} broadcast fired would otherwise never
 * learn the engine already knows about a newer release. The replayed message is built
 * from the same {@link NewerRelease} the broadcast was (#466) — version and Releases-page
 * url — so a late joiner sees the identical banner, link included.
 *
 * <p>The greeting also carries {@code releaseUrl} (#799), the Releases-page link for the
 * version this build <em>is</em>, not a newer one — built the same way {@code
 * ReleaseUpdateChecker} builds a release's own url, but without a network round-trip:
 * every permanent release's tag is {@code v<release>} by this project's own tagging
 * convention (see {@code scripts/release.sh}), so the url is assembled directly from
 * {@code locklane.release-check.repository} and the running version. Omitted for a
 * {@code -SNAPSHOT} build, which was never tagged and has no release page to link to.
 *
 * <p>A connection is also caught up on which agents are waiting for the user (#790):
 * {@code consoleAttention} is otherwise push-only, broadcast by {@link SessionRegistry}
 * only at the moment a session's state changes (#130), so a page opened, reloaded or
 * reconnected after an agent rang the bell would show it as calm until it rang again.
 * The new connection is sent one {@code consoleAttention} message with
 * {@code state: "waiting"} per live session currently waiting — the exact shape the
 * live broadcast uses, so every consumer catches up with no new message type — and
 * nothing for a session that is active. The snapshot is taken only <em>after</em> the
 * connection is registered for broadcasts, so a state change racing the connect is
 * delivered by the broadcast or the snapshot (at worst by both, which is idempotent),
 * never lost between the two. Same audience as the broadcast: every connected client,
 * regardless of project.
 *
 * <p>Also runs {@link TerminalHeartbeat} on every live connection (#665), the same
 * mechanism {@link TerminalWebSocketHandler} uses for {@code /ws/sessions/*} (#279):
 * neither side otherwise has any way to notice a proxy idle timeout, a laptop sleeping,
 * or a throttled background tab silently dropping this socket, and without a
 * {@code close} event the client's own reconnect logic never fires, so every later
 * broadcast — {@code consolesChanged} included — is lost until a manual reload. A
 * {@code @Component} (unlike before #665) so its {@link Scheduled} tick is actually
 * picked up by Spring's scheduler, the same requirement {@link TerminalWebSocketHandler}
 * is already built around.
 *
 * <p>What gets registered — with the broadcaster and the heartbeat alike — is the
 * serializing wrapper {@link TerminalHeartbeat#serialized} puts around the connection
 * (#761), never the raw session: a broadcast from a scheduler, HTTP, or PTY drain
 * thread and the heartbeat's ping then take one lock per connection instead of colliding
 * inside Tomcat. The greeting goes through the same wrapper, so no write to a connection
 * ever bypasses it.
 *
 * <p>Each tick also broadcasts an application-level {@code {"type":"heartbeat"}} text
 * message to every live connection (#762), because the protocol ping above is invisible
 * to the browser: JavaScript is never told a ping arrived, so a client whose own TCP leg
 * has died — behind a proxy, the engine's and the browser's connections are separate
 * legs, and the engine closing its side never reaches the browser — keeps an OPEN socket
 * that will never deliver anything, with no signal to act on. The text message is that
 * signal: the client tracks when it last received <em>any</em> message and reconnects on
 * its own once more than two intervals pass without one. The greeting carries the
 * interval ({@code heartbeatIntervalMs}) so the two sides agree on it without the client
 * hardcoding a number. The broadcast goes through {@link EventBroadcaster}, so it reaches
 * exactly the registered wrappers, and one connection's failing write is contained the
 * same way any broadcast's is (#761).
 */
@Component
public class EventsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventsWebSocketHandler.class);

    /**
     * The type of the application-level liveness message (#762). Carries no other
     * fields; the client filters it out before any consumer sees the stream.
     */
    static final String HEARTBEAT_TYPE = "heartbeat";

    private final EventBroadcaster broadcaster;
    private final String versionStamp;
    private final String runningVersion;
    private final Optional<String> releaseUrl;
    private final Supplier<Optional<NewerRelease>> newerRelease;
    // #790: the live sessions waiting for attention, and why (#854), read fresh on
    // every connect — a supplier for the same reason newerRelease is: the set changes
    // throughout the engine's lifetime.
    private final Supplier<Collection<SessionRegistry.WaitingSession>> waitingSessions;
    private final TerminalHeartbeat heartbeat;
    private final long heartbeatIntervalMs;

    @Autowired
    public EventsWebSocketHandler(EventBroadcaster broadcaster, BuildProperties buildProperties,
            @Value("${locklane.release-check.repository}") String repository,
            ReleaseUpdateChecker releaseUpdateChecker, SessionRegistry sessionRegistry, Clock clock,
            @Value("${locklane.events.heartbeat-interval-ms}") long heartbeatIntervalMs) {
        this(broadcaster, buildProperties.getTime().toString(), buildProperties.getVersion(), repository,
                releaseUpdateChecker::newerReleaseAvailable, sessionRegistry::waitingSessions, clock,
                heartbeatIntervalMs);
    }

    /**
     * Test-only: a fixed stamp/version/repository and a fake release supplier, with no
     * session waiting, without needing a real {@link BuildProperties}, {@link
     * ReleaseUpdateChecker} or {@link SessionRegistry} — mirrors
     * {@link TerminalWebSocketHandler}'s own test-only constructor.
     */
    EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            String repository, Supplier<Optional<NewerRelease>> newerRelease) {
        this(broadcaster, versionStamp, runningVersion, repository, newerRelease, List::of, Clock.systemUTC(),
                20_000L);
    }

    /** Test-only: as above, with a fake supplier of the waiting sessions (#790, #854). */
    EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            Supplier<Optional<NewerRelease>> newerRelease, Supplier<Collection<SessionRegistry.WaitingSession>> waitingSessions) {
        this(broadcaster, versionStamp, runningVersion, "o/r", newerRelease, waitingSessions, Clock.systemUTC(),
                20_000L);
    }

    /** Test-only: as the five-arg constructor above, with a controllable {@link Clock} (#762). */
    EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            String repository, Supplier<Optional<NewerRelease>> newerRelease, Clock clock, long heartbeatIntervalMs) {
        this(broadcaster, versionStamp, runningVersion, repository, newerRelease, List::of, clock,
                heartbeatIntervalMs);
    }

    /** Test-only: as the five-arg waiting-sessions constructor above, with a controllable {@link Clock}. */
    EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            Supplier<Optional<NewerRelease>> newerRelease, Supplier<Collection<SessionRegistry.WaitingSession>> waitingSessions,
            Clock clock, long heartbeatIntervalMs) {
        this(broadcaster, versionStamp, runningVersion, "o/r", newerRelease, waitingSessions, clock,
                heartbeatIntervalMs);
    }

    /** Package-visible so a heartbeat test can drive this with a controllable {@link Clock}. */
    EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            String repository, Supplier<Optional<NewerRelease>> newerRelease,
            Supplier<Collection<SessionRegistry.WaitingSession>> waitingSessions, Clock clock, long heartbeatIntervalMs) {
        this.broadcaster = broadcaster;
        this.versionStamp = versionStamp;
        this.runningVersion = runningVersion;
        this.releaseUrl = runningVersion.endsWith("-SNAPSHOT") ? Optional.empty()
                : Optional.of("https://github.com/" + repository + "/releases/tag/v" + runningVersion);
        this.newerRelease = newerRelease;
        this.waitingSessions = waitingSessions;
        this.heartbeat = new TerminalHeartbeat(clock, heartbeatIntervalMs);
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession connection) {
        WebSocketSession session = TerminalHeartbeat.serialized(connection);
        Map<String, Object> greeting = new LinkedHashMap<>();
        greeting.put("version", versionStamp);
        greeting.put("release", runningVersion);
        greeting.put("heartbeatIntervalMs", heartbeatIntervalMs);
        releaseUrl.ifPresent(url -> greeting.put("releaseUrl", url));
        broadcaster.sendTo(session, "engineVersion", greeting);
        newerRelease.get().ifPresent(release ->
                broadcaster.sendTo(session, "releaseAvailable",
                        Map.of("version", release.version(), "url", release.url())));
        broadcaster.register(session);
        heartbeat.track(session);
        // #790: read only now that the connection is registered, so a change that
        // races this connect reaches it as a broadcast even when the read below
        // misses it; the same change arriving twice is harmless.
        for (SessionRegistry.WaitingSession waitingSession : waitingSessions.get()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("sessionId", waitingSession.sessionId());
            fields.put("state", "waiting");
            // #854: absent only for an engine old enough to have no reason to report —
            // never the case for this handler's own registry-backed supplier, but a
            // test's fake may still hand back one with no reason.
            if (waitingSession.reason() != null) {
                fields.put("reason", waitingSession.reason().wireValue());
            }
            // #861: the agent's own message, when one was seen — absent otherwise.
            if (waitingSession.message() != null) {
                fields.put("message", waitingSession.message());
            }
            broadcaster.sendTo(session, "consoleAttention", fields);
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeat.recordPong(session);
    }

    /**
     * Detects a stale/half-open {@code /ws/events} connection within a bounded time
     * (#665) — see {@link TerminalHeartbeat} — and then tells every connection still
     * live that the engine is here (#762): the protocol ping the tick sends is answered
     * by the browser itself and never reaches its JavaScript, so this is what the
     * client's own liveness check watches for. Tick first, so a connection the tick
     * just closed for missing its pongs is not written to again. The interval is
     * configurable ({@code locklane.events.heartbeat-interval-ms}) so a test can run
     * this on a much shorter cycle than production without changing the code.
     */
    @Scheduled(fixedDelayString = "${locklane.events.heartbeat-interval-ms}")
    void sendHeartbeats() {
        try {
            heartbeat.tick();
        } catch (RuntimeException e) {
            log.error("Scheduled events heartbeat failed", e);
        }
        try {
            broadcaster.broadcast(HEARTBEAT_TYPE);
        } catch (RuntimeException e) {
            log.error("Scheduled events heartbeat message failed", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // Spring hands back the raw session, not the wrapper afterConnectionEstablished
        // registered; both registries key by id, which the wrapper delegates, so this
        // still removes the right entry.
        broadcaster.unregister(session);
        heartbeat.untrack(session);
    }
}
