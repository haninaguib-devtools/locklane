package dev.locklane.engine.github;

import java.util.Optional;

/**
 * Fetches this repository's own latest permanent release (#287), as opposed to
 * {@link GhClient}, which is scoped per managed project. Unlike {@link GhClient}'s
 * methods, a failure here is absorbed rather than thrown: "no permanent release yet" and
 * "gh failed" both mean the same thing to a caller deciding whether to show the update
 * banner — nothing to compare against, so it stays hidden.
 */
public interface ReleaseClient {

    /** Empty when the repo has no permanent release yet, or the lookup failed. */
    Optional<GhRelease> latestRelease();
}
