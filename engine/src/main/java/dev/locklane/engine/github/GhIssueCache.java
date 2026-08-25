package dev.locklane.engine.github;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the last successfully fetched issue and PR lists in memory and refreshes
 * them together on a timer, so a request never has to wait on a live {@code gh} call
 * once the cache is warm (#4's done-when). A refresh failure keeps serving the last
 * good data rather than clearing it — a transient gh/network hiccup should not make
 * the sidenav empty.
 */
@Service
public class GhIssueCache {

    private static final long REFRESH_INTERVAL_MS = 30_000;

    // Task branches are wip/<id>-<slug> (AGENTS.md) — the same convention /t-work
    // uses to derive a branch name from an issue.
    private static final Pattern WIP_BRANCH = Pattern.compile("^wip/(\\d+)-");

    private final GhClient ghClient;
    private final AtomicReference<List<GhIssue>> cachedIssues = new AtomicReference<>();
    private final AtomicReference<List<GhPullRequest>> cachedPullRequests = new AtomicReference<>();

    public GhIssueCache(GhClient ghClient) {
        this.ghClient = ghClient;
    }

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = REFRESH_INTERVAL_MS)
    void refresh() {
        try {
            cachedIssues.set(ghClient.issues());
            cachedPullRequests.set(ghClient.pullRequests());
        } catch (GhClient.GhUnavailableException e) {
            // Keep serving whatever is already cached; the next scheduled attempt
            // may succeed. A cache that was never populated stays null here, and
            // the accessors below fall back to a live fetch.
        }
    }

    /** All issues. Serves the cache when warm; falls back to a live fetch when cold. */
    public List<GhIssue> issues() {
        List<GhIssue> snapshot = cachedIssues.get();
        if (snapshot != null) {
            return snapshot;
        }
        List<GhIssue> fresh = ghClient.issues();
        cachedIssues.set(fresh);
        return fresh;
    }

    public Optional<GhIssue> issue(int number) {
        return issues().stream().filter(i -> i.number() == number).findFirst();
    }

    /** All PRs. Serves the cache when warm; falls back to a live fetch when cold. */
    public List<GhPullRequest> pullRequests() {
        List<GhPullRequest> snapshot = cachedPullRequests.get();
        if (snapshot != null) {
            return snapshot;
        }
        List<GhPullRequest> fresh = ghClient.pullRequests();
        cachedPullRequests.set(fresh);
        return fresh;
    }

    /**
     * The PR whose branch belongs to this issue, if one exists. The newest PR wins
     * if an issue ever had more than one (matches how the branch-naming convention
     * is meant to be used — one task branch per issue at a time).
     */
    public Optional<GhPullRequest> pullRequestForIssue(int issueNumber) {
        GhPullRequest latest = null;
        for (GhPullRequest pr : pullRequests()) {
            Matcher m = WIP_BRANCH.matcher(pr.headRefName());
            if (m.find() && Integer.parseInt(m.group(1)) == issueNumber) {
                if (latest == null || pr.number() > latest.number()) {
                    latest = pr;
                }
            }
        }
        return Optional.ofNullable(latest);
    }
}
