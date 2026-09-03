package dev.locklane.engine.github;

import java.util.List;

/**
 * The sidenav's issue-tree payload (#619): the tree itself plus the outcome of the
 * project's most recent GitHub fetch, so a 200 carrying data the engine could not
 * refresh is distinguishable from one that is genuinely up to date.
 */
public record TreeResponse(List<TreeNode> nodes, GhRefreshStatus github) {
}
