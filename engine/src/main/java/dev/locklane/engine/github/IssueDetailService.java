package dev.locklane.engine.github;

import dev.locklane.engine.process.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Composes {@link GhIssueCache} (cached issue/PR lists), a live per-PR detail fetch
 * (reviews/checks — not cached, see #16's issue body), and a task-record filesystem
 * lookup into the "?" popup's data and the flow-state strip. One instance per
 * project since #81 — {@code projectRoot} is that project's own workarea (where its
 * own docs/tasks/, if any, would live), not a Spring-managed singleton itself.
 */
public class IssueDetailService {

    private static final Logger log = LoggerFactory.getLogger(IssueDetailService.class);

    // A real "## Plan" is a heading /t-plan writes at the start of a line — matching
    // it as a bare substring is a false positive waiting to happen: issue #16's own
    // body mentions "## Plan" in prose (describing this very feature) and would
    // otherwise read as having a plan section it does not have.
    private static final Pattern PLAN_HEADING = Pattern.compile("(?m)^## Plan\\b");

    /** Mirrors {@code WorktreeCreationService.DEFAULT_TRUNK} (#582) — duplicated rather than shared since that one is persistence-package-private and this is the only other place it is needed. */
    private static final String DEFAULT_TRUNK = "main";

    private final GhIssueCache cache;
    private final GhClient ghClient;
    private final Path projectRoot;
    private final String trunkRef;

    /**
     * {@code defaultBranch} is the project's recorded default branch (#582), or
     * {@code null}/blank for a project row that predates that field — resolved to
     * {@code origin/<branch>} (or {@code origin/}{@link #DEFAULT_TRUNK}) the same way
     * {@code WorktreeCreationService} resolves its own trunk ref.
     */
    public IssueDetailService(GhIssueCache cache, GhClient ghClient, String projectRoot, String defaultBranch) {
        this.cache = cache;
        this.ghClient = ghClient;
        this.projectRoot = Path.of(projectRoot).normalize();
        String branch = defaultBranch == null || defaultBranch.isBlank() ? DEFAULT_TRUNK : defaultBranch.strip();
        this.trunkRef = "origin/" + branch;
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

    /**
     * The task record's path relative to the project root, e.g. docs/tasks/892-....md
     * or docs/tasks/000000/16-....md. Resolved against {@link #trunkRef} — the fetched
     * trunk, {@code origin/<default branch>} — rather than the holder checkout's own
     * working tree (#896): the holder is a detached checkout the engine only ever
     * re-detaches, never advances, so a record landed since can sit on the trunk while
     * the checkout on disk is still whatever commit it was cloned or last detached at.
     * Falls back to the on-disk scan only when {@link #trunkRef} does not resolve at
     * all — a project that has never fetched, or (in tests) no git repository here.
     */
    private Optional<String> recordPath(int number) {
        if (run("git", "-C", projectRoot.toString(), "rev-parse", "--verify", "--quiet", trunkRef).exitCode() == 0) {
            return recordPathFromTrunk(number);
        }
        return recordPathFromDisk(number);
    }

    /** Same precedence as {@link #recordPathFromDisk}: flat first, then each bucket directory. */
    private Optional<String> recordPathFromTrunk(int number) {
        Map<String, Boolean> top = lsTree("docs/tasks");
        Optional<String> flat = matchingRecord(top, number);
        if (flat.isPresent()) {
            return Optional.of("docs/tasks/" + flat.get());
        }
        for (Map.Entry<String, Boolean> entry : top.entrySet()) {
            if (!entry.getValue()) {
                continue; // a file at this level, not a bucket directory
            }
            String bucket = entry.getKey();
            Optional<String> match = matchingRecord(lsTree("docs/tasks/" + bucket), number);
            if (match.isPresent()) {
                return Optional.of("docs/tasks/" + bucket + "/" + match.get());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> matchingRecord(Map<String, Boolean> entries, int number) {
        String prefix = number + "-";
        return entries.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .filter(name -> name.startsWith(prefix) && name.endsWith(".md"))
                .findFirst();
    }

    /** {@link #trunkRef}'s direct entries under {@code path}, as name → is-a-directory. Empty when {@code path} does not exist in that tree. */
    private Map<String, Boolean> lsTree(String path) {
        ProcessOutcome result = run("git", "-C", projectRoot.toString(), "ls-tree", trunkRef + ":" + path);
        if (result.failed()) {
            return Map.of();
        }
        Map<String, Boolean> entries = new LinkedHashMap<>();
        for (String line : result.stdout().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            entries.put(line.substring(tab + 1), line.substring(0, tab).contains(" tree "));
        }
        return entries;
    }

    /** The pre-#896 on-disk scan, kept as the fallback for a project whose {@link #trunkRef} does not exist yet. */
    private Optional<String> recordPathFromDisk(int number) {
        Path tasks = projectRoot.resolve("docs/tasks");
        if (!Files.isDirectory(tasks)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> flat = Files.newDirectoryStream(tasks, number + "-*.md")) {
            for (Path record : flat) {
                return Optional.of(projectRoot.relativize(record).toString());
            }
        } catch (IOException e) {
            log.warn("Could not scan {} for issue {}'s flat task record", tasks, number, e);
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
            log.warn("Could not scan {} for issue {}'s task record", tasks, number, e);
            return Optional.empty();
        }
        return Optional.empty();
    }

    private ProcessOutcome run(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new ProcessOutcome(exit, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while running git {} in {}", String.join(" ", command), projectRoot, e);
            return new ProcessOutcome(-1, "", "interrupted");
        } catch (IOException e) {
            log.warn("Could not run git {} in {} — is it installed and on PATH?", String.join(" ", command),
                    projectRoot, e);
            return new ProcessOutcome(-1, "", e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
