package dev.locklane.engine.github;

import java.util.List;

/** The "?" popup's data: task record, checks, branch & PR, and the flow-state strip. */
public record IssueDetail(
        int number,
        String recordPath,   // null: no record yet
        ChecksSummary checks,
        String branch,       // null: no PR
        Integer prNumber,    // null: no PR
        String prState,      // null: no PR
        boolean prDraft,
        List<FlowStep> flowSteps) {
}
