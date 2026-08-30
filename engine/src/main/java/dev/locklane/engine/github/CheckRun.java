package dev.locklane.engine.github;

/**
 * One CI check on a PR: the name GitHub shows for it, its outcome as
 * passing/failing/pending, and the URL of the run it came from ({@code null} when the
 * rollup carries no link — a status context with no target, say).
 */
public record CheckRun(String name, String state, String url) {

    public static final String PASSING = "passing";
    public static final String FAILING = "failing";
    public static final String PENDING = "pending";
}
