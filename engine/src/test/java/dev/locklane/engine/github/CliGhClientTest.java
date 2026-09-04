package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers #397 (the rollup parse keeps each check's name, outcome, and link) and #671
 * (a missing working directory is reported as such, never as a missing gh).
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
}
