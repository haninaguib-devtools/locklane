package dev.locklane.engine.ws;

import dev.locklane.engine.github.ReleaseUpdateChecker;
import dev.locklane.engine.github.ReleaseUpdateChecker.NewerRelease;
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
 * <p>Also runs {@link TerminalHeartbeat} on every live connection (#665), the same
 * mechanism {@link TerminalWebSocketHandler} uses for {@code /ws/sessions/*} (#279):
 * neither side otherwise has any way to notice a proxy idle timeout, a laptop sleeping,
 * or a throttled background tab silently dropping this socket, and without a
 * {@code close} event the client's own reconnect logic never fires, so every later
 * broadcast — {@code consolesChanged} included — is lost until a manual reload. A
 * {@code @Component} (unlike before #665) so its {@link Scheduled} tick is actually
 * picked up by Spring's scheduler, the same requirement {@link TerminalWebSocketHandler}
 * is already built around.
 */
@Component
public class EventsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventsWebSocketHandler.class);

    private final EventBroadcaster broadcaster;
    private final String versionStamp;
    private final String runningVersion;
    private final Supplier<Optional<NewerRelease>> newerRelease;
    private final TerminalHeartbeat heartbeat;

    @Autowired
    public EventsWebSocketHandler(EventBroadcaster broadcaster, BuildProperties buildProperties,
            ReleaseUpdateChecker releaseUpdateChecker, Clock clock,
            @Value("${locklane.events.heartbeat-interval-ms}") long heartbeatIntervalMs) {
        this(broadcaster, buildProperties.getTime().toString(), buildProperties.getVersion(),
                releaseUpdateChecker::newerReleaseAvailable, clock, heartbeatIntervalMs);
    }

    /**
     * Test-only: a fixed stamp/version and a fake supplier, without needing a real
     * {@link BuildProperties} or {@link ReleaseUpdateChecker} — mirrors
     * {@link TerminalWebSocketHandler}'s own test-only constructor.
     */
    EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            Supplier<Optional<NewerRelease>> newerRelease) {
        this(broadcaster, versionStamp, runningVersion, newerRelease, Clock.systemUTC(), 20_000L);
    }

    /** Package-visible so a heartbeat test can drive this with a controllable {@link Clock}. */
    EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            Supplier<Optional<NewerRelease>> newerRelease, Clock clock, long heartbeatIntervalMs) {
        this.broadcaster = broadcaster;
        this.versionStamp = versionStamp;
        this.runningVersion = runningVersion;
        this.newerRelease = newerRelease;
        this.heartbeat = new TerminalHeartbeat(clock, heartbeatIntervalMs);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.sendTo(session, "engineVersion",
                Map.of("version", versionStamp, "release", runningVersion));
        newerRelease.get().ifPresent(release ->
                broadcaster.sendTo(session, "releaseAvailable",
                        Map.of("version", release.version(), "url", release.url())));
        broadcaster.register(session);
        heartbeat.track(session);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        heartbeat.recordPong(session);
    }

    /**
     * Detects a stale/half-open {@code /ws/events} connection within a bounded time
     * (#665) — see {@link TerminalHeartbeat}. The interval is configurable
     * ({@code locklane.events.heartbeat-interval-ms}) so a test can run this on a much
     * shorter cycle than production without changing the code.
     */
    @Scheduled(fixedDelayString = "${locklane.events.heartbeat-interval-ms}")
    void sendHeartbeats() {
        try {
            heartbeat.tick();
        } catch (RuntimeException e) {
            log.error("Scheduled events heartbeat failed", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
        heartbeat.untrack(session);
    }
}
