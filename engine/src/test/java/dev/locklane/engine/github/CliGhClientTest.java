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
    void staleReRunEntriesAndLegitimatelySkippedChecksDoNotCountAsFailing() throws Exception {
        // PR #899's raw rollup (#900): 3 current SUCCESS checks, 1 legitimately-SKIPPED
        // mac-lifecycle check, plus 3 stale CANCELLED/SKIPPED entries left over from an
        // earlier superseded run of the same (workflowName, name) pairs. The old code
        // counted every entry and treated SKIPPED as a failure, landing on 4 failing / 3
        // passing; the true state (gh pr checks 899) is 3 pass, 1 skip, 0 fail.
        String json = """
                {
                  "number": 899,
                  "reviews": [],
                  "statusCheckRollup": [
                    {"name": "decide", "conclusion": "CANCELLED", "workflowName": "mac-lifecycle",
                     "startedAt": "2026-09-11T20:43:00Z", "completedAt": "2026-09-11T20:43:05Z"},
                    {"name": "t-workflow", "conclusion": "SKIPPED", "workflowName": "t-workflow",
                     "startedAt": "2026-09-11T20:42:58Z", "completedAt": "2026-09-11T20:42:57Z"},
                    {"name": "build", "conclusion": "SUCCESS", "workflowName": "build",
                     "startedAt": "2026-09-11T20:43:01Z", "completedAt": "2026-09-11T20:44:10Z"},
                    {"name": "decide", "conclusion": "SUCCESS", "workflowName": "mac-lifecycle",
                     "startedAt": "2026-09-11T20:43:09Z", "completedAt": "2026-09-11T20:43:12Z"},
                    {"name": "t-workflow", "conclusion": "SUCCESS", "workflowName": "t-workflow",
                     "startedAt": "2026-09-11T20:43:08Z", "completedAt": "2026-09-11T20:43:13Z"},
                    {"name": "mac-lifecycle", "conclusion": "CANCELLED", "workflowName": "mac-lifecycle",
                     "startedAt": "2026-09-11T20:43:06Z", "completedAt": "2026-09-11T20:43:05Z"},
                    {"name": "mac-lifecycle", "conclusion": "SKIPPED", "workflowName": "mac-lifecycle",
                     "startedAt": "2026-09-11T20:43:13Z", "completedAt": "2026-09-11T20:43:12Z"}
                  ]
                }
                """;

        GhPullRequestDetail detail = CliGhClient.toPullRequestDetail(MAPPER.readTree(json));

        assertThat(detail.checks().passing()).isEqualTo(3);
        assertThat(detail.checks().failing()).isEqualTo(0);
        assertThat(detail.checks().pending()).isEqualTo(0);
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
