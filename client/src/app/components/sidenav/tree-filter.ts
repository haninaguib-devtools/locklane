import { TreeNode } from '../../models/issue.model';

/**
 * An initiative survives the filters either by matching itself (keeping all of its
 * children, still subject to the ship filter individually) or by having at least
 * one child that matches on its own (keeping only the children that do). A leaf
 * task must match the text and ship filters directly. Nesting is one level deep
 * (#21), so this never needs to recurse past a node's direct children.
 */
export function filterNode(node: TreeNode, filterText: string, hideShipped: boolean): TreeNode | null {
  const needle = filterText.trim().toLowerCase();
  const textOk = (n: TreeNode) => !needle || `#${n.number} ${n.title}`.toLowerCase().includes(needle);
  const shipOk = (n: TreeNode) => !hideShipped || n.state !== 'CLOSED';

  if (node.children.length === 0) {
    return textOk(node) && shipOk(node) ? node : null;
  }

  if (textOk(node) && shipOk(node)) {
    return { ...node, children: node.children.filter(shipOk) };
  }

  const survivingChildren = node.children.filter((c) => textOk(c) && shipOk(c));
  return survivingChildren.length > 0 ? { ...node, children: survivingChildren } : null;
}

export function filterTree(nodes: TreeNode[], filterText: string, hideShipped: boolean): TreeNode[] {
  return nodes
    .map((n) => filterNode(n, filterText, hideShipped))
    .filter((n): n is TreeNode => n !== null);
}

/**
 * A pinned entry is never removed for being shipped — only for not matching the
 * text filter. Its children are still filtered normally (text and ship both apply).
 */
export function filterPinnedNode(node: TreeNode, filterText: string, hideShipped: boolean): TreeNode | null {
  const needle = filterText.trim().toLowerCase();
  const textOk = (n: TreeNode) => !needle || `#${n.number} ${n.title}`.toLowerCase().includes(needle);
  const shipOk = (n: TreeNode) => !hideShipped || n.state !== 'CLOSED';

  if (!textOk(node)) {
    return null;
  }
  return { ...node, children: node.children.filter((c) => textOk(c) && shipOk(c)) };
}

export function filterPinnedTree(nodes: TreeNode[], filterText: string, hideShipped: boolean): TreeNode[] {
  return nodes
    .map((n) => filterPinnedNode(n, filterText, hideShipped))
    .filter((n): n is TreeNode => n !== null);
}
