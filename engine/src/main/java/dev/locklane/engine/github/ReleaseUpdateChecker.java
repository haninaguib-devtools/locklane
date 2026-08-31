package dev.locklane.engine.github;

import dev.locklane.engine.ws.EventBroadcaster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically compares this repo's latest permanent release against the version the
 * engine was built with (#287), and broadcasts {@code releaseAvailable} on
 * {@link EventBroadcaster} the moment a newer one appears. {@link #newerReleaseAvailable()}
 * is what {@code EventsWebSocketHandler} reads to tell a newly-connecting client, since a
 * connection made after the broadcast already fired would otherwise never learn the
 * state.
 *
 * <p>The payload carries the release's own Releases-page {@code url} alongside its
 * {@code version} (#466), so the client can link the banner to the release's notes; both
 * the broadcast and the on-connect replay are built from the same stored
 * {@link NewerRelease}, so a late joiner sees the identical link.
 *
 * <p>The running version never changes at runtime and a permanent release is immutable
 * once cut, so once a newer release is found it never needs to be un-found — this only
 * ever moves from absent to present, or to a higher version.
 */
@Service
public class ReleaseUpdateChecker {

    /** A newer release the engine knows about: its version and its GitHub Releases-page URL (#466). */
    public record NewerRelease(String version, String url) {

        Map<String, String> payload() {
            return Map.of("version", version, "url", url);
        }
    }

    private final ReleaseClient releaseClient;
    private final EventBroadcaster eventBroadcaster;
    private final String runningVersion;
    private final AtomicReference<NewerRelease> newerRelease = new AtomicReference<>();

    @Autowired
    public ReleaseUpdateChecker(ReleaseClient releaseClient, EventBroadcaster eventBroadcaster, BuildProperties buildProperties) {
        this(releaseClient, eventBroadcaster, buildProperties.getVersion());
    }

    ReleaseUpdateChecker(ReleaseClient releaseClient, EventBroadcaster eventBroadcaster, String runningVersion) {
        this.releaseClient = releaseClient;
        this.eventBroadcaster = eventBroadcaster;
        this.runningVersion = runningVersion;
    }

    /** The newer release known to be available, if any — what a newly-connecting client should be told. */
    public Optional<NewerRelease> newerReleaseAvailable() {
        return Optional.ofNullable(newerRelease.get());
    }

    @Scheduled(fixedDelayString = "${locklane.release-check.interval-ms}",
            initialDelayString = "${locklane.release-check.interval-ms}")
    void check() {
        Optional<GhRelease> latest = releaseClient.latestRelease();
        if (latest.isEmpty()) {
            return;
        }
        String latestVersion = latest.get().version();
        if (!isNewer(latestVersion, runningVersion)) {
            return;
        }
        NewerRelease found = new NewerRelease(latestVersion, latest.get().url());
        NewerRelease previous = newerRelease.getAndSet(found);
        if (previous == null || !found.version().equals(previous.version())) {
            eventBroadcaster.broadcast("releaseAvailable", found.payload());
        }
    }

    /**
     * Compares dot-separated numeric version parts, ignoring any {@code -SNAPSHOT} or
     * other qualifier suffix on either side — a running {@code 0.1.0-SNAPSHOT} against a
     * released {@code 0.1.0} reads as equal, not newer: the SNAPSHOT is presumably the
     * work leading up to that same release, not behind it.
     */
    static boolean isNewer(String latest, String running) {
        int[] latestParts = numericParts(latest);
        int[] runningParts = numericParts(running);
        int length = Math.max(latestParts.length, runningParts.length);
        for (int i = 0; i < length; i++) {
            int l = i < latestParts.length ? latestParts[i] : 0;
            int r = i < runningParts.length ? runningParts[i] : 0;
            if (l != r) {
                return l > r;
            }
        }
        return false;
    }

    private static int[] numericParts(String version) {
        String[] pieces = version.split("-", 2)[0].split("\\.");
        int[] parts = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                parts[i] = Integer.parseInt(pieces[i]);
            } catch (NumberFormatException e) {
                parts[i] = 0;
            }
        }
        return parts;
    }
}
