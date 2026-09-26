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
 * `hasOpenAgentSession` (#263) exempts a node with a live agent session from the text and
 * ship filters -- it stays visible however it's typed or shipped -- but not from
 * the tag filter.
 *
 * `authors` (#930, multi-select since #947) is empty-means-no-filter and OR's within
 * itself like `tags`: a node matches if its GitHub author is any of the selected
 * logins, ANDed against the other filters the same way the tag filter is (and, like
 * tags, not exempted by an open agent session).
 *
 * `hiddenTags` (#999) is the label picker's Hide column, the inverse of `tags`: a node
 * carrying *any* of them is dropped, ANDed against the other filters. It is part of
 * the tag filter, so an open agent session does not exempt a node from it either.
 */
export function filterNode(
  node: TreeNode,
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenAgentSession: (n: TreeNode) => boolean = () => false,
  authors: string[] = [],
  hiddenTags: string[] = [],
): TreeNode | null {
  const needle = filterText.trim().toLowerCase();
  const textOk = (n: TreeNode) =>
    !needle || `#${n.number} ${n.title}`.toLowerCase().includes(needle) || hasOpenAgentSession(n);
  const shipOk = (n: TreeNode) => !hideShipped || n.state !== 'CLOSED' || hasOpenAgentSession(n);
  const tagOk = (n: TreeNode) =>
    (tags.length === 0 || n.labels.some((l) => tags.includes(l))) &&
    !n.labels.some((l) => hiddenTags.includes(l));
  const authorOk = (n: TreeNode) => authors.length === 0 || authors.includes(n.author);
  const selfOk = (n: TreeNode) => textOk(n) && shipOk(n) && tagOk(n) && authorOk(n);

  if (node.children.length === 0) {
    return selfOk(node) ? node : null;
  }

  if (selfOk(node)) {
    return { ...node, children: node.children.filter((c) => shipOk(c) && tagOk(c) && authorOk(c)) };
  }

  const survivingChildren = node.children.filter((c) => textOk(c) && shipOk(c) && tagOk(c) && authorOk(c));
  return survivingChildren.length > 0 ? { ...node, children: survivingChildren } : null;
}

export function filterTree(
  nodes: TreeNode[],
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenAgentSession: (n: TreeNode) => boolean = () => false,
  authors: string[] = [],
  hiddenTags: string[] = [],
): TreeNode[] {
  return nodes
    .map((n) => filterNode(n, filterText, hideShipped, tags, hasOpenAgentSession, authors, hiddenTags))
    .filter((n): n is TreeNode => n !== null);
}

/**
 * A pinned entry is never removed for being shipped — only for not matching the
 * text filter. Its children are still filtered normally (text, ship, tag and author
 * all apply). `hasOpenAgentSession` (#263) exempts a node from the text filter too, on
 * top of the always-on ship exemption pinning already gives it. The author filter
 * (#930, a list since #947) follows the tag filter's rule for pins: it never removes
 * the pin itself, and neither does `hiddenTags` (#999), which drops hidden children only.
 */
export function filterPinnedNode(
  node: TreeNode,
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenAgentSession: (n: TreeNode) => boolean = () => false,
  authors: string[] = [],
  hiddenTags: string[] = [],
): TreeNode | null {
  const needle = filterText.trim().toLowerCase();
  const textOk = (n: TreeNode) =>
    !needle || `#${n.number} ${n.title}`.toLowerCase().includes(needle) || hasOpenAgentSession(n);
  const shipOk = (n: TreeNode) => !hideShipped || n.state !== 'CLOSED' || hasOpenAgentSession(n);
  const tagOk = (n: TreeNode) =>
    (tags.length === 0 || n.labels.some((l) => tags.includes(l))) &&
    !n.labels.some((l) => hiddenTags.includes(l));
  const authorOk = (n: TreeNode) => authors.length === 0 || authors.includes(n.author);

  if (!textOk(node)) {
    return null;
  }
  return {
    ...node,
    children: node.children.filter((c) => textOk(c) && shipOk(c) && tagOk(c) && authorOk(c)),
  };
}

export function filterPinnedTree(
  nodes: TreeNode[],
  filterText: string,
  hideShipped: boolean,
  tags: string[] = [],
  hasOpenAgentSession: (n: TreeNode) => boolean = () => false,
  authors: string[] = [],
  hiddenTags: string[] = [],
): TreeNode[] {
  return nodes
    .map((n) => filterPinnedNode(n, filterText, hideShipped, tags, hasOpenAgentSession, authors, hiddenTags))
    .filter((n): n is TreeNode => n !== null);
}
