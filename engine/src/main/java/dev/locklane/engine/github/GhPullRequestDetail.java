package dev.locklane.engine.github;

/** A single PR's reviews and CI status — fetched live, not cached (#16). */
public record GhPullRequestDetail(int number, int reviewCount, ChecksSummary checks) {
}
