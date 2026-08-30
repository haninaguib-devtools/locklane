package dev.locklane.engine.github;

import java.util.List;

/**
 * A PR's CI status (statusCheckRollup): the pass/fail/pending counts, plus the
 * individual checks behind them in the order GitHub returned them (#397).
 */
public record ChecksSummary(int passing, int failing, int pending, List<CheckRun> runs) {

    public static ChecksSummary none() {
        return new ChecksSummary(0, 0, 0, List.of());
    }
}
