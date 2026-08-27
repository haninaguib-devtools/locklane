package dev.locklane.engine.github;

import java.util.List;

/** One node in the initiative/task hierarchy: an initiative with its direct
 * sub-tasks nested beneath it, or a standalone task with no children (#21).
 * {@code hasActiveBranch} mirrors {@link IssueDetail#branch()}'s presence (#110) --
 * the same PR-derived signal, just a boolean since the tree has no per-issue detail
 * fetch to hang the branch name itself off of. {@code labels} mirrors
 * {@link GhIssue#labels()} verbatim (#111) -- the sidebar's tag filter picks its own
 * classification subset out of these rather than the tree pre-filtering them. */
public record TreeNode(
        int number, String title, String kind, String state, boolean hasActiveBranch, List<String> labels, List<TreeNode> children) {
}
