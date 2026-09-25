package dev.locklane.engine.github;

import java.util.List;
import java.util.Optional;

/**
 * The gh CLI behind an interface, so {@link GhIssueCache} and detail-fetching code
 * are testable without shelling out to a real process. gh is already the tool this
 * project's own pipeline requires (docs/adapters/TRACKER.md), so no separate GitHub
 * token/client setup is needed to fetch data for the app's own UI.
 */
public interface GhClient {

    /** All issues, open and closed, with no cap on how many (#991). */
    List<GhIssue> issues();

    /** All PRs, open and closed, with no cap on how many (#991). */
    List<GhPullRequest> pullRequests();

    /**
     * Whether this client answers {@link #probeChanges}, {@link #issuesUpdatedSince} and
     * {@link #pullRequestsUpdatedSince} (#991). Default {@code false}: {@link GhIssueCache}
     * then refreshes with a full {@link #issues()} + {@link #pullRequests()} fetch every
     * time, exactly as before #991, so the many fakes in the test suite need not change.
     */
    default boolean supportsIncrementalRefresh() {
        return false;
    }

    /**
     * One conditional request telling whether anything in the repo's issues or PRs
     * changed since the response {@code etag} came from (#991); {@code etag} null makes
     * it unconditional. A not-modified answer does not count against GitHub's rate limit.
     */
    default ChangeProbe probeChanges(String etag) {
        throw new UnsupportedOperationException();
    }

    /** Issues (not PRs) updated at or after {@code since}, an ISO-8601 timestamp GitHub itself reported (#991). */
    default List<GhIssue> issuesUpdatedSince(String since) {
        throw new UnsupportedOperationException();
    }

    /** PRs updated at or after {@code since}, an ISO-8601 timestamp GitHub itself reported (#991). */
    default List<GhPullRequest> pullRequestsUpdatedSince(String since) {
        throw new UnsupportedOperationException();
    }

    /** One PR's reviews and CI status, or empty if it does not exist. */
    Optional<GhPullRequestDetail> pullRequestDetail(int number);

    /**
     * Every label defined in the repo, not just labels currently on some loaded
     * issue. Default throws: only {@link CliGhClient} and the no-checkout stand-in
     * need answer this, so the many {@code GhClient} fakes elsewhere in the test
     * suite that never call it are left alone (#962).
     */
    default List<GhLabel> labels() {
        throw new UnsupportedOperationException();
    }

    /**
     * Adds and removes labels on one issue in a single {@code gh} call; either list
     * may be empty. Same default-throws rationale as {@link #labels()}.
     */
    default void updateIssueLabels(int number, List<String> add, List<String> remove) {
        throw new UnsupportedOperationException();
    }

    /**
     * What {@link #probeChanges} learned (#991). {@code modified} false means GitHub
     * answered 304 and nothing needs fetching. Otherwise {@code etag} is the value to
     * send next time, and {@code newestUpdatedAt} is the most recent {@code updated_at}
     * across the repo's issues and PRs ({@code null} for a repo with none) — the
     * watermark the next incremental fetch asks for changes since.
     */
    record ChangeProbe(boolean modified, String etag, String newestUpdatedAt) {

        static ChangeProbe notModified() {
            return new ChangeProbe(false, null, null);
        }
    }

    /** Thrown when gh fails, or its output cannot be parsed. */
    class GhUnavailableException extends RuntimeException {
        public GhUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
