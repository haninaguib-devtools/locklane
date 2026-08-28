# 263 — Exempt issues with an open console from sidenav filters
Issue: #263

## Asked
In the sidenav issue list, an issue with a live console attached should always stay
visible, even when the active filters would otherwise hide it — the "Open" filter
(which hides closed issues) or the text/search filter (which hides issues that don't
match the typed text). Today, closing an issue with a running console, or typing a
search term that doesn't match it, makes the issue disappear from the list while its
console is still live underneath it.

## Done when
- An issue with an open console stays in the sidenav list when the "Open" checkbox is
  checked, even if the issue's state is `CLOSED`.
- An issue with an open console stays in the sidenav list regardless of the typed
  search text.
- An issue with no open console is unaffected: existing Open-filter and text-filter
  behavior is unchanged for it.
- Existing sidenav/tree-filter tests still pass, and new tests cover the exemption for
  both the Open filter and the text filter.

## Explicitly not
Does not change the tag filter's behavior, and does not change how the console-dot
indicator itself is rendered.

## Decisions made along the way
- `filterNode`/`filterTree`/`filterPinnedNode`/`filterPinnedTree` (tree-filter.ts) take
  a new optional `hasOpenConsole: (n: TreeNode) => boolean` predicate (default
  `() => false`, so every existing call site keeps its old behavior), folded into the
  existing `textOk`/`shipOk` closures as an OR — exempting console-open nodes from both
  the text and ship filters while leaving `tagOk` untouched, per the issue's non-goal.
  (hani, 2026-08-28)
- `sidenav.component.ts`'s two call sites (`pinnedGroups`, `mainNodesFor`) pass
  `(n) => this.hasOpenConsole(section.project.id, n.number)`, reusing the existing
  `openConsoleIssues` tracking that already drives the console-dot indicator. (hani,
  2026-08-28)

## Deviations / notes
- none
