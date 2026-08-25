package dev.locklane.engine.github;

import java.util.List;

/** One node in the initiative/task hierarchy: an initiative with its direct
 * sub-tasks nested beneath it, or a standalone task with no children (#21). */
public record TreeNode(int number, String title, String kind, String state, List<TreeNode> children) {
}
