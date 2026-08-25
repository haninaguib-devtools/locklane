package dev.locklane.engine.github;

/** One GitHub PR, the fields correlating it to an issue and its lifecycle need. */
public record GhPullRequest(
        int number,
        String title,
        String state,
        boolean isDraft,
        String headRefName) {
}
