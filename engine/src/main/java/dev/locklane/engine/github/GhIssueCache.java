package dev.locklane.engine.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
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
    private final Clock clock;
    private final AtomicReference<List<GhIssue>> cachedIssues = new AtomicReference<>();
    private final AtomicReference<List<GhPullRequest>> cachedPullRequests = new AtomicReference<>();
    // The outcome of the most recent fetch (#619) -- refresh() and the cold-cache
    // fallbacks below all record here, so a token that stopped working is visible
    // from the very first fetch that fails, not only from the next scheduled poll.
    private final AtomicReference<GhRefreshStatus> status = new AtomicReference<>(GhRefreshStatus.initial());
    // #991: the change probe's ETag and newest updated_at from the last successful
    // refresh -- what the next one asks "changed since?" with. Guarded by this.
    private String etag;
    private String watermark;
    // #995: when the last successful full fetch finished; null until one has.
    private Instant lastFullFetchAt;

    public GhIssueCache(GhClient ghClient) {
        this(ghClient, Clock.systemUTC());
    }

    GhIssueCache(GhClient ghClient, Clock clock) {
        this.ghClient = ghClient;
        this.clock = clock;
    }

    /** The outcome of this project's most recent GitHub fetch (#619). */
    public GhRefreshStatus status() {
        return status.get();
    }

    /**
     * Refreshes the cache and reports whether the fetched issue or PR set differs
     * from what was cached before (#129) — {@link GhIssue} and {@link GhPullRequest}
     * are records, so list equality is a structural, field-by-field comparison.
     * {@code false} on a failed fetch (nothing changed; the old data is still being
     * served) and on a fetch that came back identical to what was already cached.
     *
     * <p>With a client that supports it (#991), a warm cache first asks GitHub one
     * conditional question — has anything changed since the last fetch? — and does
     * nothing more on a 304, which costs no rate limit. When something did change, only
     * the issues and PRs updated since the last fetch are fetched and merged into the
     * cached lists, so an unchanged issue's body is never downloaded again. A cold cache
     * fetches everything.
     */
    boolean refresh() {
        return refresh(true, false);
    }

    /**
     * As {@link #refresh()}, but never takes a 304 for an answer (#991): used right
     * after the engine itself changed an issue (#962), when the change probe's own
     * listing could lag the write by a moment and report nothing new. Still fetches
     * only what changed since the last fetch.
     */
    boolean refreshAfterWrite() {
        return refresh(false, false);
    }

    /**
     * A full re-fetch of every issue and PR, whatever the cache holds (#991) — what
     * picks up an issue that was deleted or transferred away, something an
     * incremental fetch cannot see.
     */
    boolean refreshFully() {
        return refresh(false, true);
    }

    /**
     * The scheduled poll's refresh (#995): {@link #refreshFully()} when no full fetch
     * has succeeded within {@code fullFetchInterval}, {@link #refresh()} otherwise. A
     * cold cache's first refresh is always a full one, so the server's first poll
     * after starting is too.
     */
    boolean refreshOnSchedule(Duration fullFetchInterval) {
        Instant last;
        synchronized (this) {
            last = lastFullFetchAt;
        }
        boolean due = last == null || !Duration.between(last, clock.instant()).minus(fullFetchInterval).isNegative();
        return due ? refreshFully() : refresh();
    }

    /**
     * Synchronized (#991) so the scheduled poll and a request-driven refresh never
     * interleave their reads and writes of the ETag and watermark below.
     */
    private synchronized boolean refresh(boolean conditional, boolean full) {
        List<GhIssue> previousIssues = cachedIssues.get();
        List<GhPullRequest> previousPullRequests = cachedPullRequests.get();
        try {
            List<GhIssue> freshIssues;
            List<GhPullRequest> freshPullRequests;
            if (!ghClient.supportsIncrementalRefresh()) {
                freshIssues = ghClient.issues();
                freshPullRequests = ghClient.pullRequests();
                lastFullFetchAt = clock.instant();
            } else {
                boolean incremental = !full && previousIssues != null && previousPullRequests != null
                        && etag != null && watermark != null;
                GhClient.ChangeProbe probe = ghClient.probeChanges(incremental && conditional ? etag : null);
                if (!probe.modified()) {
                    recordSuccess();
                    return false;
                }
                if (incremental) {
                    freshIssues = merge(previousIssues, ghClient.issuesUpdatedSince(watermark), GhIssue::number);
                    freshPullRequests = merge(previousPullRequests, ghClient.pullRequestsUpdatedSince(watermark),
                            GhPullRequest::number);
                } else {
                    freshIssues = merge(List.of(), ghClient.issues(), GhIssue::number);
                    freshPullRequests = merge(List.of(), ghClient.pullRequests(), GhPullRequest::number);
                    lastFullFetchAt = clock.instant();
                }
                // Only now that the fetch succeeded: a failed one must leave the old
                // ETag in place, so the next probe still sees the change it missed.
                etag = probe.etag();
                watermark = probe.newestUpdatedAt();
            }
            cachedIssues.set(freshIssues);
            cachedPullRequests.set(freshPullRequests);
            recordSuccess();
            return !Objects.equals(previousIssues, freshIssues) || !Objects.equals(previousPullRequests, freshPullRequests);
        } catch (GhClient.GhUnavailableException e) {
            // Keep serving whatever is already cached; the next scheduled attempt
            // may succeed. A cache that was never populated stays null here, and
            // the accessors below fall back to a live fetch.
            log.warn("Issue/PR refresh failed; continuing to serve the previously cached data", e);
            recordFailure(e);
            return false;
        }
    }

    /**
     * {@code base} with every item of {@code updates} replacing the one with the same
     * number, or added (#991), newest number first — the one order both a full fetch
     * and an incremental one end in, so the two produce equal lists. A duplicate
     * within {@code updates} (a page boundary moving mid-fetch) keeps its first copy.
     */
    private static <T> List<T> merge(List<T> base, List<T> updates, ToIntFunction<T> number) {
        Map<Integer, T> byNumber = new HashMap<>();
        for (T item : base) {
            byNumber.put(number.applyAsInt(item), item);
        }
        Set<Integer> updated = new HashSet<>();
        for (T item : updates) {
            if (updated.add(number.applyAsInt(item))) {
                byNumber.put(number.applyAsInt(item), item);
            }
        }
        List<T> merged = new ArrayList<>(byNumber.values());
        merged.sort(Comparator.comparingInt(number).reversed());
        return List.copyOf(merged);
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
            recordSuccess();
            return fresh;
        } catch (GhClient.GhUnavailableException e) {
            log.warn("Live issue fetch failed with a cold cache; reporting no issues", e);
            recordFailure(e);
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
            recordSuccess();
            return fresh;
        } catch (GhClient.GhUnavailableException e) {
            log.warn("Live PR fetch failed with a cold cache; reporting no PRs", e);
            recordFailure(e);
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

    private void recordSuccess() {
        status.updateAndGet(current -> current.succeeded(clock.instant()));
    }

    /**
     * The exception's own message is the text a person can act on -- for a failed
     * gh run, {@code CliGhClient} already folds the process's stderr (e.g. {@code HTTP
     * 401: Bad credentials}) into it.
     */
    private void recordFailure(GhClient.GhUnavailableException e) {
        String failure = e.getMessage() == null || e.getMessage().isBlank() ? "GitHub is unavailable" : e.getMessage();
        status.updateAndGet(current -> current.failed(failure));
    }
}
