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
    void recordPathIsFoundAtTheFlatLayoutLocationOnTheFetchedTrunkEvenWhenTheHolderCheckoutIsStale(@TempDir Path root)
            throws IOException, InterruptedException {
        // The record exists on origin/main, but the holder checkout (HEAD) never
        // advanced past the commit before it landed — exactly #896's staleness bug.
        initRepoWithRecordOnlyOnOrigin(root, "docs/tasks/16-fetch-pr-checks-data.md", "# 16");

        IssueDetailService service = service(root, List.of(issue(16, "OPEN", "")), List.of(), Optional.empty());

        assertThat(service.detail(16).orElseThrow().recordPath())
                .isEqualTo("docs/tasks/16-fetch-pr-checks-data.md");
    }

    @Test
    void recordPathIsFoundUnderItsBucketDirectoryOnTheFetchedTrunkEvenWhenTheHolderCheckoutIsStale(@TempDir Path root)
            throws IOException, InterruptedException {
        initRepoWithRecordOnlyOnOrigin(root, "docs/tasks/000000/16-fetch-pr-checks-data.md", "# 16");

        IssueDetailService service = service(root, List.of(issue(16, "OPEN", "")), List.of(), Optional.empty());

        assertThat(service.detail(16).orElseThrow().recordPath())
                .isEqualTo("docs/tasks/000000/16-fetch-pr-checks-data.md");
    }

    @Test
    void recordPathFallsBackToTheOnDiskScanWhenOriginHasNeverBeenFetched(@TempDir Path root)
            throws IOException, InterruptedException {
        // A real repo with no refs/remotes/origin/main at all -- a project that has
        // never fetched -- so recordPath() must fall back to scanning the checkout.
        run(root, "git", "init", "--quiet", "-b", "main");
        Files.createDirectories(root.resolve("docs/tasks"));
        Files.writeString(root.resolve("docs/tasks/16-fetch-pr-checks-data.md"), "# 16");

        IssueDetailService service = service(root, List.of(issue(16, "OPEN", "")), List.of(), Optional.empty());

        assertThat(service.detail(16).orElseThrow().recordPath())
                .isEqualTo("docs/tasks/16-fetch-pr-checks-data.md");
    }

    /**
     * Builds a real repo at {@code root} whose {@code refs/remotes/origin/main}
     * carries {@code recordRelativePath} but whose checked-out {@code main} branch
     * (the stand-in for the holder's own working tree) was reset back to the commit
     * before it landed -- so a plain on-disk scan would find nothing.
     */
    private static void initRepoWithRecordOnlyOnOrigin(Path root, String recordRelativePath, String content)
            throws IOException, InterruptedException {
        run(root, "git", "init", "--quiet", "-b", "main");
        run(root, "git", "config", "user.email", "test@example.com");
        run(root, "git", "config", "user.name", "Test");
        run(root, "git", "commit", "--quiet", "--allow-empty", "-m", "base");
        String baseSha = run(root, "git", "rev-parse", "HEAD").strip();

        Path record = root.resolve(recordRelativePath);
        Files.createDirectories(record.getParent());
        Files.writeString(record, content);
        run(root, "git", "add", recordRelativePath);
        run(root, "git", "commit", "--quiet", "-m", "add record");
        String recordSha = run(root, "git", "rev-parse", "HEAD").strip();

        run(root, "git", "update-ref", "refs/remotes/origin/main", recordSha);
        run(root, "git", "reset", "--quiet", "--hard", baseSha);
    }

    private static String run(Path cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
        }
        return output;
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
        return new IssueDetailService(cache, fake, root.toString(), "main");
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
