package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #16's done-when: record path, checks, branch & PR, and flow-state. */
class IssueDetailServiceTest {

    @Test
    void unknownIssueIsEmpty(@TempDir Path root) {
        IssueDetailService service = service(root, List.of(), List.of(), Optional.empty());

        assertThat(service.detail(404)).isEmpty();
    }

    @Test
    void anIssueWithNoPlanAndNoPrShowsOnlyOpenDone(@TempDir Path root) {
        IssueDetailService service = service(root,
                List.of(issue(1, "OPEN", "## Goal\n\nSomething.")), List.of(), Optional.empty());

        IssueDetail detail = service.detail(1).orElseThrow();

        assertThat(detail.recordPath()).isNull();
        assertThat(detail.branch()).isNull();
        assertThat(detail.prNumber()).isNull();
        assertThat(steps(detail)).containsExactly(
                new FlowStep("open", true),
                new FlowStep("plan", false),
                new FlowStep("work", false),
                new FlowStep("review", false),
                new FlowStep("ship", false));
    }

    @Test
    void aPlanSectionInTheBodyMarksThePlanStepDone(@TempDir Path root) {
        IssueDetailService service = service(root,
                List.of(issue(1, "OPEN", "## Goal\n\nX\n\n## Plan\n\nY")), List.of(), Optional.empty());

        assertThat(step(service, 1, "plan")).isTrue();
    }

    @Test
    void merelyMentioningPlanInProseDoesNotCountAsHavingOne(@TempDir Path root) {
        // Regression: issue #16's own body says "the issue body containing `## Plan`"
        // as prose, which a bare substring check would misread as a real section.
        String body = "## Goal\n\nDerive flow-state from whether the body contains `## Plan`.";
        IssueDetailService service = service(root, List.of(issue(1, "OPEN", body)), List.of(), Optional.empty());

        assertThat(step(service, 1, "plan")).isFalse();
    }

    @Test
    void anOpenPrWithNoReviewsMarksWorkDoneButNotReview(@TempDir Path root) {
        GhPullRequest pr = new GhPullRequest(50, "PR", "OPEN", true, "wip/1-slug");
        IssueDetailService service = service(root, List.of(issue(1, "OPEN", "")), List.of(pr),
                Optional.of(new GhPullRequestDetail(50, 0, new ChecksSummary(2, 0, 1, List.of()))));

        IssueDetail detail = service.detail(1).orElseThrow();

        assertThat(detail.branch()).isEqualTo("wip/1-slug");
        assertThat(detail.prNumber()).isEqualTo(50);
        assertThat(detail.prState()).isEqualTo("OPEN");
        assertThat(detail.prDraft()).isTrue();
        assertThat(detail.checks()).isEqualTo(new ChecksSummary(2, 0, 1, List.of()));
        assertThat(step(service, 1, "work")).isTrue();
        assertThat(step(service, 1, "review")).isFalse();
    }

    @Test
    void aReviewedPrMarksReviewDone(@TempDir Path root) {
        GhPullRequest pr = new GhPullRequest(50, "PR", "OPEN", false, "wip/1-slug");
        IssueDetailService service = service(root, List.of(issue(1, "OPEN", "")), List.of(pr),
                Optional.of(new GhPullRequestDetail(50, 1, ChecksSummary.none())));

        assertThat(step(service, 1, "review")).isTrue();
    }

    @Test
    void aMergedPrMarksEveryStepDone(@TempDir Path root) {
        GhPullRequest pr = new GhPullRequest(50, "PR", "MERGED", false, "wip/1-slug");
        IssueDetailService service = service(root, List.of(issue(1, "OPEN", "")), List.of(pr), Optional.empty());

        assertThat(steps(service.detail(1).orElseThrow())).allMatch(FlowStep::done);
    }

    @Test
    void aClosedIssueWithNoPrMarksEveryStepDone(@TempDir Path root) {
        IssueDetailService service = service(root, List.of(issue(1, "CLOSED", "")), List.of(), Optional.empty());

        assertThat(steps(service.detail(1).orElseThrow())).allMatch(FlowStep::done);
    }

    @Test
    void recordPathIsFoundUnderItsBucketDirectory(@TempDir Path root) throws IOException {
        Path bucket = root.resolve("docs/tasks/000000");
        Files.createDirectories(bucket);
        Files.writeString(bucket.resolve("16-fetch-pr-checks-data.md"), "# 16");

        IssueDetailService service = service(root, List.of(issue(16, "OPEN", "")), List.of(), Optional.empty());

        assertThat(service.detail(16).orElseThrow().recordPath())
                .isEqualTo("docs/tasks/000000/16-fetch-pr-checks-data.md");
    }

    private static boolean step(IssueDetailService service, int number, String name) {
        return steps(service.detail(number).orElseThrow()).stream()
                .filter(s -> s.name().equals(name))
                .findFirst().orElseThrow().done();
    }

    private static List<FlowStep> steps(IssueDetail detail) {
        return detail.flowSteps();
    }

    private static GhIssue issue(int number, String state, String body) {
        return new GhIssue(number, "Issue " + number, state, List.of(), body, "", "");
    }

    private static IssueDetailService service(Path root, List<GhIssue> issues, List<GhPullRequest> prs,
            Optional<GhPullRequestDetail> prDetail) {
        FakeGhClient fake = new FakeGhClient(issues, prs, prDetail);
        GhIssueCache cache = new GhIssueCache(fake);
        return new IssueDetailService(cache, fake, root.toString());
    }

    private static final class FakeGhClient implements GhClient {
        private final List<GhIssue> issues;
        private final List<GhPullRequest> pullRequests;
        private final Optional<GhPullRequestDetail> detail;

        FakeGhClient(List<GhIssue> issues, List<GhPullRequest> pullRequests, Optional<GhPullRequestDetail> detail) {
            this.issues = issues;
            this.pullRequests = pullRequests;
            this.detail = detail;
        }

        @Override
        public List<GhIssue> issues() {
            return issues;
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return pullRequests;
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return detail;
        }
    }
}
