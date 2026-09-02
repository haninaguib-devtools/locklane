package dev.locklane.engine.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the last successfully fetched issue and PR lists in memory for one project's
 * {@link GhClient}, so a request never has to wait on a live {@code gh} call once the
 * cache is warm (#4's done-when). A refresh failure keeps serving the last good data
 * rather than clearing it — a transient gh/network hiccup should not make the sidenav
 * empty. One instance per project since #81 — built and scheduled for refresh by
 * {@code ProjectGhResources}, not a Spring-managed singleton itself.
 */
public class GhIssueCache {

    private static final Logger log = LoggerFactory.getLogger(GhIssueCache.class);

    // Task branches are wip/<id>-<slug> (AGENTS.md) — the same convention /t-work
    // uses to derive a branch name from an issue.
    private static final Pattern WIP_BRANCH = Pattern.compile("^wip/(\\d+)-");

    private final GhClient ghClient;
    private final AtomicReference<List<GhIssue>> cachedIssues = new AtomicReference<>();
    private final AtomicReference<List<GhPullRequest>> cachedPullRequests = new AtomicReference<>();

    public GhIssueCache(GhClient ghClient) {
        this.ghClient = ghClient;
    }

    /**
     * Refreshes the cache and reports whether the fetched issue or PR set differs
     * from what was cached before (#129) — {@link GhIssue} and {@link GhPullRequest}
     * are records, so list equality is a structural, field-by-field comparison.
     * {@code false} on a failed fetch (nothing changed; the old data is still being
     * served) and on a fetch that came back identical to what was already cached.
     */
    boolean refresh() {
        List<GhIssue> previousIssues = cachedIssues.get();
        List<GhPullRequest> previousPullRequests = cachedPullRequests.get();
        try {
            List<GhIssue> freshIssues = ghClient.issues();
            List<GhPullRequest> freshPullRequests = ghClient.pullRequests();
            cachedIssues.set(freshIssues);
            cachedPullRequests.set(freshPullRequests);
            return !Objects.equals(previousIssues, freshIssues) || !Objects.equals(previousPullRequests, freshPullRequests);
        } catch (GhClient.GhUnavailableException e) {
            // Keep serving whatever is already cached; the next scheduled attempt
            // may succeed. A cache that was never populated stays null here, and
            // the accessors below fall back to a live fetch.
            log.warn("Issue/PR refresh failed; continuing to serve the previously cached data", e);
            return false;
        }
    }

    /**
     * All issues. Serves the cache when warm; falls back to a live fetch when cold.
     * A failed live fetch (no token stored yet, or the project's repo is otherwise
     * unreachable, #81) returns an empty list — a clear, documented result rather
     * than a thrown exception surfacing as a 500.
     */
    public List<GhIssue> issues() {
        List<GhIssue> snapshot = cachedIssues.get();
        if (snapshot != null) {
            return snapshot;
        }
        try {
            List<GhIssue> fresh = ghClient.issues();
            cachedIssues.set(fresh);
            return fresh;
        } catch (GhClient.GhUnavailableException e) {
            log.warn("Live issue fetch failed with a cold cache; reporting no issues", e);
            return List.of();
        }
    }

    public Optional<GhIssue> issue(int number) {
        return issues().stream().filter(i -> i.number() == number).findFirst();
    }

    /** All PRs. Serves the cache when warm; falls back to a live fetch when cold, empty on failure (#81). */
    public List<GhPullRequest> pullRequests() {
        List<GhPullRequest> snapshot = cachedPullRequests.get();
        if (snapshot != null) {
            return snapshot;
        }
        try {
            List<GhPullRequest> fresh = ghClient.pullRequests();
            cachedPullRequests.set(fresh);
            return fresh;
        } catch (GhClient.GhUnavailableException e) {
            log.warn("Live PR fetch failed with a cold cache; reporting no PRs", e);
            return List.of();
        }
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
