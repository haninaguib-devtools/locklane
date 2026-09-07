package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers #397 (the rollup parse keeps each check's name, outcome, and link), #671 (a
 * missing working directory is reported as such, never as a missing gh), and #763
 * (a gh that never exits is killed and reported within the timeout, with nothing
 * left running; a gh that floods stderr first still gets its stdout read).
 */
class CliGhClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aMissingWorkingDirectoryIsNamedInsteadOfBlamingGhsPath(@TempDir Path dir) {
        Path missing = dir.resolve("not-cloned-yet");
        CliGhClient client = new CliGhClient(missing, null);

        assertThatThrownBy(client::issues)
                .isInstanceOf(GhClient.GhUnavailableException.class)
                .hasMessageContaining(missing.toString())
                .hasMessageNotContaining("PATH");
    }

    // #763: a stalled gh is bounded. The fake gh records its own pid and its child's,
    // so the test can check that neither outlives the call -- killing only the parent
    // would orphan the sleep, still holding the output pipes open.

    @Test
    void aGhThatNeverExitsFailsWithinTheTimeoutAndLeavesNoProcessBehind(@TempDir Path dir) throws Exception {
        Path pids = dir.resolve("pids");
        Path fakeGh = FakeGh.script(dir, """
                echo $$ >> "%s"
                sleep 1000 &
                echo $! >> "%s"
                wait
                """.formatted(pids, pids));
        CliGhClient client = new CliGhClient(dir, null, fakeGh.toString(), Duration.ofMillis(500));

        long started = System.nanoTime();
        assertThatThrownBy(client::issues)
                .isInstanceOf(GhClient.GhUnavailableException.class)
                .hasMessageContaining("did not exit within 500 ms and was killed");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
        List<Long> recorded = Files.readAllLines(pids).stream().map(Long::parseLong).toList();
        assertThat(recorded).hasSize(2);
        for (long pid : recorded) {
            assertThat(FakeGh.gone(pid)).as("process %d should have been killed", pid).isTrue();
        }
    }

    @Test
    void issuesAreReadEvenWhenGhFloodsStderrBeforeWritingStdout(@TempDir Path dir) throws Exception {
        // Reading stdout to EOF before touching stderr -- the pre-#763 code -- hangs
        // here: stderr's pipe fills at 64 KiB, the script blocks on it, and stdout is
        // never written. The timeout turns that hang into a failure rather than a
        // stuck test.
        Path fakeGh = FakeGh.script(dir, """
                head -c 1000000 /dev/zero | tr '\\0' x >&2
                echo '[{"number": 1, "title": "One", "state": "OPEN", "labels": []}]'
                """);
        CliGhClient client = new CliGhClient(dir, null, fakeGh.toString(), Duration.ofSeconds(20));

        List<GhIssue> issues = client.issues();

        assertThat(issues).extracting(GhIssue::number).containsExactly(1);
    }

    @Test
    void aNonzeroExitReportsGhsOwnStderrText(@TempDir Path dir) throws Exception {
        // ProjectGhResources looks for "Bad credentials" in this message (#656).
        Path fakeGh = FakeGh.script(dir, """
                echo 'HTTP 401: Bad credentials (https://api.github.com/graphql)' >&2
                exit 1
                """);
        CliGhClient client = new CliGhClient(dir, null, fakeGh.toString(), Duration.ofSeconds(20));

        assertThatThrownBy(client::issues)
                .isInstanceOf(GhClient.GhUnavailableException.class)
                .hasMessageContaining("gh exited 1: HTTP 401: Bad credentials");
    }

    @Test
    void keepsEveryChecksNameOutcomeAndLink() throws Exception {
        String json = """
                {
                  "number": 7,
                  "reviews": [],
                  "statusCheckRollup": [
                    {"name": "record", "conclusion": "SUCCESS",
                     "detailsUrl": "https://github.com/o/r/actions/runs/1/job/10"},
                    {"name": "consistency", "conclusion": "FAILURE",
                     "detailsUrl": "https://github.com/o/r/actions/runs/1/job/11"},
                    {"name": "build", "conclusion": "",
                     "detailsUrl": "https://github.com/o/r/actions/runs/1/job/12"},
                    {"context": "legacy/status", "conclusion": "SUCCESS"}
                  ]
                }
                """;

        GhPullRequestDetail detail = CliGhClient.toPullRequestDetail(MAPPER.readTree(json));

        assertThat(detail.number()).isEqualTo(7);
        assertThat(detail.checks().passing()).isEqualTo(2);
        assertThat(detail.checks().failing()).isEqualTo(1);
        assertThat(detail.checks().pending()).isEqualTo(1);
        assertThat(detail.checks().runs()).containsExactly(
                new CheckRun("record", "passing", "https://github.com/o/r/actions/runs/1/job/10"),
                new CheckRun("consistency", "failing", "https://github.com/o/r/actions/runs/1/job/11"),
                new CheckRun("build", "pending", "https://github.com/o/r/actions/runs/1/job/12"),
                new CheckRun("legacy/status", "passing", null));
    }

    @Test
    void aStatusContextsTargetUrlIsUsedWhenThereIsNoDetailsUrl() throws Exception {
        String json = """
                {"number": 7, "reviews": [], "statusCheckRollup": [
                  {"context": "ci/external", "conclusion": "SUCCESS",
                   "targetUrl": "https://ci.example/build/3"}]}
                """;

        GhPullRequestDetail detail = CliGhClient.toPullRequestDetail(MAPPER.readTree(json));

        assertThat(detail.checks().runs()).containsExactly(
                new CheckRun("ci/external", "passing", "https://ci.example/build/3"));
    }

    @Test
    void aPrWithNoCheckRunsHasNoRuns() throws Exception {
        String json = """
                {"number": 7, "reviews": [], "statusCheckRollup": []}
                """;

        GhPullRequestDetail detail = CliGhClient.toPullRequestDetail(MAPPER.readTree(json));

        assertThat(detail.checks()).isEqualTo(ChecksSummary.none());
    }

    /** A stand-in for gh written into a temp dir, for the process-level tests (#763). */
    static final class FakeGh {
        private FakeGh() {
        }

        static Path script(Path dir, String body) throws IOException {
            Path script = dir.resolve("fake-gh");
            Files.writeString(script, "#!/usr/bin/env bash\n" + body);
            if (!script.toFile().setExecutable(true)) {
                throw new IOException("could not make " + script + " executable");
            }
            return script;
        }

        /**
         * {@code true} once no live process has this pid. A killed grandchild is a
         * zombie until whoever inherited it reaps it, which can take a moment, so this
         * polls briefly rather than looking once.
         */
        static boolean gone(long pid) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        }
    }
}
