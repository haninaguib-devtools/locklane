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

    /** All issues, open and closed — one live gh call. */
    List<GhIssue> issues();

    /** All PRs, open and closed — one live gh call. */
    List<GhPullRequest> pullRequests();

    /** One PR's reviews and CI status, or empty if it does not exist. */
    Optional<GhPullRequestDetail> pullRequestDetail(int number);

    /** Thrown when gh fails, or its output cannot be parsed. */
    class GhUnavailableException extends RuntimeException {
        public GhUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
