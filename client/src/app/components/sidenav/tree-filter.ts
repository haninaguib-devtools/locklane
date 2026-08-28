import { TreeNode } from '../../models/issue.model';

/**
 * An initiative survives the filters either by matching itself (keeping all of its
 * children, still subject to the ship filter individually) or by having at least
 * one child that matches on its own (keeping only the children that do). A leaf
 * task must match the text and ship filters directly. Nesting is one level deep
 * (#21), so this never needs to recurse past a node's direct children.
 *
 * `tags` is empty-means-no-filter, and OR's within itself when non-empty (#111): a
 * node matches if it carries *any* of the selected tags, ANDed against the other
 * filters.
 *
 * `hasOpenConsole` (#263) exempts a node with a live console from the text and
 * ship filters -- it stays visible however it's typed or shipped -- but not from
 * the tag filter.
 */
export function filterNode(
  node: TreeNode,
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenConsole: (n: TreeNode) => boolean = () => false,
): TreeNode | null {
  const needle = filterText.trim().toLowerCase();
  const textOk = (n: TreeNode) =>
    !needle || `#${n.number} ${n.title}`.toLowerCase().includes(needle) || hasOpenConsole(n);
  const shipOk = (n: TreeNode) => !hideShipped || n.state !== 'CLOSED' || hasOpenConsole(n);
  const tagOk = (n: TreeNode) => tags.length === 0 || n.labels.some((l) => tags.includes(l));
  const selfOk = (n: TreeNode) => textOk(n) && shipOk(n) && tagOk(n);

  if (node.children.length === 0) {
    return selfOk(node) ? node : null;
  }

  if (selfOk(node)) {
    return { ...node, children: node.children.filter((c) => shipOk(c) && tagOk(c)) };
  }

  const survivingChildren = node.children.filter((c) => textOk(c) && shipOk(c) && tagOk(c));
  return survivingChildren.length > 0 ? { ...node, children: survivingChildren } : null;
}

export function filterTree(
  nodes: TreeNode[],
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenConsole: (n: TreeNode) => boolean = () => false,
): TreeNode[] {
  return nodes
    .map((n) => filterNode(n, filterText, hideShipped, tags, hasOpenConsole))
    .filter((n): n is TreeNode => n !== null);
}

/**
 * A pinned entry is never removed for being shipped — only for not matching the
 * text filter. Its children are still filtered normally (text, ship, and tag all
 * apply). `hasOpenConsole` (#263) exempts a node from the text filter too, on top
 * of the always-on ship exemption pinning already gives it.
 */
export function filterPinnedNode(
  node: TreeNode,
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenConsole: (n: TreeNode) => boolean = () => false,
): TreeNode | null {
  const needle = filterText.trim().toLowerCase();
  const textOk = (n: TreeNode) =>
    !needle || `#${n.number} ${n.title}`.toLowerCase().includes(needle) || hasOpenConsole(n);
  const shipOk = (n: TreeNode) => !hideShipped || n.state !== 'CLOSED' || hasOpenConsole(n);
  const tagOk = (n: TreeNode) => tags.length === 0 || n.labels.some((l) => tags.includes(l));

  if (!textOk(node)) {
    return null;
  }
  return { ...node, children: node.children.filter((c) => textOk(c) && shipOk(c) && tagOk(c)) };
}

export function filterPinnedTree(
  nodes: TreeNode[],
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenConsole: (n: TreeNode) => boolean = () => false,
): TreeNode[] {
  return nodes
    .map((n) => filterPinnedNode(n, filterText, hideShipped, tags, hasOpenConsole))
    .filter((n): n is TreeNode => n !== null);
}
