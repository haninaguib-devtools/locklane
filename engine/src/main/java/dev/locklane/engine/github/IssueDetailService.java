package dev.locklane.engine.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Composes {@link GhIssueCache} (cached issue/PR lists), a live per-PR detail fetch
 * (reviews/checks — not cached, see #16's issue body), and a task-record filesystem
 * lookup into the "?" popup's data and the flow-state strip.
 */
@Service
public class IssueDetailService {

    // A real "## Plan" is a heading /t-plan writes at the start of a line — matching
    // it as a bare substring is a false positive waiting to happen: issue #16's own
    // body mentions "## Plan" in prose (describing this very feature) and would
    // otherwise read as having a plan section it does not have.
    private static final Pattern PLAN_HEADING = Pattern.compile("(?m)^## Plan\\b");

    private final GhIssueCache cache;
    private final GhClient ghClient;
    private final Path projectRoot;

    public IssueDetailService(GhIssueCache cache, GhClient ghClient,
            @Value("${locklane.project-root}") String projectRoot) {
        this.cache = cache;
        this.ghClient = ghClient;
        this.projectRoot = Path.of(projectRoot).normalize();
    }

    public Optional<IssueDetail> detail(int number) {
        Optional<GhIssue> issueOpt = cache.issue(number);
        if (issueOpt.isEmpty()) {
            return Optional.empty();
        }
        GhIssue issue = issueOpt.get();
        Optional<GhPullRequest> prOpt = cache.pullRequestForIssue(number);
        Optional<GhPullRequestDetail> prDetailOpt = prOpt.flatMap(pr -> ghClient.pullRequestDetail(pr.number()));

        ChecksSummary checks = prDetailOpt.map(GhPullRequestDetail::checks).orElse(ChecksSummary.none());
        int reviewCount = prDetailOpt.map(GhPullRequestDetail::reviewCount).orElse(0);

        return Optional.of(new IssueDetail(
                number,
                recordPath(number).orElse(null),
                checks,
                prOpt.map(GhPullRequest::headRefName).orElse(null),
                prOpt.map(GhPullRequest::number).orElse(null),
                prOpt.map(GhPullRequest::state).orElse(null),
                prOpt.map(GhPullRequest::isDraft).orElse(false),
                flowSteps(issue, prOpt, reviewCount)));
    }

    private List<FlowStep> flowSteps(GhIssue issue, Optional<GhPullRequest> pr, int reviewCount) {
        boolean closed = "CLOSED".equals(issue.state());
        boolean merged = pr.map(p -> "MERGED".equals(p.state())).orElse(false);
        boolean shipped = merged || closed;
        boolean hasPlan = PLAN_HEADING.matcher(issue.body()).find();
        return List.of(
                new FlowStep("open", true),
                new FlowStep("plan", hasPlan || shipped),
                new FlowStep("work", pr.isPresent() || shipped),
                new FlowStep("review", reviewCount > 0 || shipped),
                new FlowStep("ship", shipped));
    }

    /** The task record's path relative to the project root, e.g. docs/tasks/000000/16-....md. */
    private Optional<String> recordPath(int number) {
        Path tasks = projectRoot.resolve("docs/tasks");
        if (!Files.isDirectory(tasks)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(tasks, Files::isDirectory)) {
            for (Path bucket : buckets) {
                try (DirectoryStream<Path> records = Files.newDirectoryStream(bucket, number + "-*.md")) {
                    for (Path record : records) {
                        return Optional.of(projectRoot.relativize(record).toString());
                    }
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
