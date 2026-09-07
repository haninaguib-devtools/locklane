package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link CliReleaseClient} against a fake gh script (#763): the call is bounded
 * and a hung gh leaves nothing running, and the happy path still parses the release.
 */
class CliReleaseClientTest {

    @Test
    void parsesTheReleaseGhReportsForTheConfiguredRepository(@TempDir Path dir) throws Exception {
        Path args = dir.resolve("args");
        Path fakeGh = CliGhClientTest.FakeGh.script(dir, """
                printf '%%s\\n' "$@" > "%s"
                echo '{"tagName": "v0.2.19", "url": "https://github.com/o/r/releases/tag/v0.2.19"}'
                """.formatted(args));
        CliReleaseClient client = new CliReleaseClient("o/r", fakeGh.toString(), Duration.ofSeconds(20));

        Optional<GhRelease> release = client.latestRelease();

        assertThat(release).contains(new GhRelease("v0.2.19", "https://github.com/o/r/releases/tag/v0.2.19"));
        assertThat(Files.readAllLines(args)).containsExactly("release", "view", "--repo", "o/r", "--json", "tagName,url");
    }

    @Test
    void aNonzeroExitIsNoRelease(@TempDir Path dir) throws Exception {
        Path fakeGh = CliGhClientTest.FakeGh.script(dir, """
                echo 'release not found' >&2
                exit 1
                """);
        CliReleaseClient client = new CliReleaseClient("o/r", fakeGh.toString(), Duration.ofSeconds(20));

        assertThat(client.latestRelease()).isEmpty();
    }

    @Test
    void aGhThatNeverExitsIsNoReleaseWithinTheTimeoutAndLeavesNoProcessBehind(@TempDir Path dir) throws Exception {
        Path pids = dir.resolve("pids");
        Path fakeGh = CliGhClientTest.FakeGh.script(dir, """
                echo $$ >> "%s"
                sleep 1000 &
                echo $! >> "%s"
                wait
                """.formatted(pids, pids));
        CliReleaseClient client = new CliReleaseClient("o/r", fakeGh.toString(), Duration.ofMillis(500));

        long started = System.nanoTime();
        Optional<GhRelease> release = client.latestRelease();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(release).isEmpty();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        List<Long> recorded = Files.readAllLines(pids).stream().map(Long::parseLong).toList();
        assertThat(recorded).hasSize(2);
        for (long pid : recorded) {
            assertThat(CliGhClientTest.FakeGh.gone(pid)).as("process %d should have been killed", pid).isTrue();
        }
    }
}
