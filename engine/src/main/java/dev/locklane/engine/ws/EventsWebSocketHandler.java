package dev.locklane.engine.ws;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

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
 * the one running is already known about (#287) — {@code newerReleaseVersion} is a
 * supplier rather than a fixed value because that state can flip at any point during the
 * engine's lifetime, unlike the version stamp above, which is fixed at build time. A
 * connection made after the one-time {@code releaseAvailable} broadcast fired would
 * otherwise never learn the engine already knows about a newer release.
 */
public class EventsWebSocketHandler extends TextWebSocketHandler {

    private final EventBroadcaster broadcaster;
    private final String versionStamp;
    private final String runningVersion;
    private final Supplier<Optional<String>> newerReleaseVersion;

    public EventsWebSocketHandler(EventBroadcaster broadcaster, String versionStamp, String runningVersion,
            Supplier<Optional<String>> newerReleaseVersion) {
        this.broadcaster = broadcaster;
        this.versionStamp = versionStamp;
        this.runningVersion = runningVersion;
        this.newerReleaseVersion = newerReleaseVersion;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.sendTo(session, "engineVersion",
                Map.of("version", versionStamp, "release", runningVersion));
        newerReleaseVersion.get().ifPresent(version ->
                broadcaster.sendTo(session, "releaseAvailable", Map.of("version", version)));
        broadcaster.register(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }
}
