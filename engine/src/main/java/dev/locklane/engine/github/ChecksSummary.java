package dev.locklane.engine.github;

/** Pass/fail/pending counts from a PR's CI status (statusCheckRollup). */
public record ChecksSummary(int passing, int failing, int pending) {

    public static ChecksSummary none() {
        return new ChecksSummary(0, 0, 0);
    }
}
