package dev.locklane.engine.github;

import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Covers #287's done-when directly, against a fake {@link ReleaseClient} — no real gh process. */
class ReleaseUpdateCheckerTest {

    @Test
    void checkDoesNothingWhenNoPermanentReleaseExistsYet() {
        // Only the rolling "latest" pre-release exists -- ReleaseClient reports that
        // the same way it reports any other failure.
        FakeReleaseClient client = new FakeReleaseClient(Optional.empty());
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(client, broadcaster, "0.1.0-SNAPSHOT");

        checker.check();

        assertThat(checker.newerVersionAvailable()).isEmpty();
        verifyNoInteractions(broadcaster);
    }

    @Test
    void checkDoesNothingWhenTheLatestReleaseIsNotNewerThanTheRunningVersion() {
        FakeReleaseClient client = new FakeReleaseClient(Optional.of(new GhRelease("v0.1.0")));
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(client, broadcaster, "0.1.0-SNAPSHOT");

        checker.check();

        assertThat(checker.newerVersionAvailable()).isEmpty();
        verifyNoInteractions(broadcaster);
    }

    @Test
    void checkBroadcastsAndRecordsTheNewerVersionWhenTheLatestReleaseIsAhead() {
        FakeReleaseClient client = new FakeReleaseClient(Optional.of(new GhRelease("v0.2.0")));
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(client, broadcaster, "0.1.0-SNAPSHOT");

        checker.check();

        assertThat(checker.newerVersionAvailable()).contains("0.2.0");
        verify(broadcaster).broadcast("releaseAvailable", Map.of("version", "0.2.0"));
    }

    @Test
    void checkBroadcastsOnlyOnceForTheSameNewerVersionAcrossRepeatedChecks() {
        FakeReleaseClient client = new FakeReleaseClient(Optional.of(new GhRelease("v0.2.0")));
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(client, broadcaster, "0.1.0-SNAPSHOT");
        checker.check();

        checker.check();

        verify(broadcaster).broadcast("releaseAvailable", Map.of("version", "0.2.0"));
    }

    @Test
    void checkBroadcastsAgainWhenAFurtherReleaseIsCutLater() {
        FakeReleaseClient client = new FakeReleaseClient(Optional.of(new GhRelease("v0.2.0")));
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(client, broadcaster, "0.1.0-SNAPSHOT");
        checker.check();

        client.setLatest(Optional.of(new GhRelease("v0.3.0")));
        checker.check();

        assertThat(checker.newerVersionAvailable()).contains("0.3.0");
        verify(broadcaster).broadcast("releaseAvailable", Map.of("version", "0.3.0"));
    }

    @Test
    void isNewerIgnoresASnapshotQualifierOnTheRunningVersion() {
        assertThat(ReleaseUpdateChecker.isNewer("0.1.0", "0.1.0-SNAPSHOT")).isFalse();
    }

    @Test
    void isNewerComparesNumericPartsRatherThanLexically() {
        // Lexical comparison would say "0.9.0" > "0.10.0" -- numeric comparison must not.
        assertThat(ReleaseUpdateChecker.isNewer("0.10.0", "0.9.0")).isTrue();
        assertThat(ReleaseUpdateChecker.isNewer("0.9.0", "0.10.0")).isFalse();
    }

    @Test
    void isNewerIsFalseForAnEqualVersion() {
        assertThat(ReleaseUpdateChecker.isNewer("0.1.0", "0.1.0")).isFalse();
    }

    private static final class FakeReleaseClient implements ReleaseClient {
        private Optional<GhRelease> latest;

        FakeReleaseClient(Optional<GhRelease> latest) {
            this.latest = latest;
        }

        void setLatest(Optional<GhRelease> latest) {
            this.latest = latest;
        }

        @Override
        public Optional<GhRelease> latestRelease() {
            return latest;
        }
    }
}
