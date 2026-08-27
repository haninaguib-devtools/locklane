# 111 — Filter issues by type tag
Issue: #111 · Part of: #109

## Asked
Once issues carry classification tags (#104), add a sidebar filter that narrows the
issue list by tag (e.g. bug, feature, docs).

## Done when
- `tree-filter.ts` supports filtering by one or more tag values, ANDed with the
  existing text/hide-shipped/branch filters.
- The sidebar UI exposes a way to pick which tag(s) to filter by (e.g. a dropdown or
  chip selector), scoped to the tag values #104 actually produces.
- Manual check: tag a couple of issues with different types, confirm the filter
  narrows the list to the selected tag(s).

## Explicitly not
No change to how tags are assigned — that's #104's scope.

## Decisions made along the way
- `TreeNode` (both the engine record and the client model) gains a `labels: string[]`
  field, populated straight from `GhIssue.labels()` — the same field #104 already
  fetches and caches, just plumbed onto the tree the sidebar reads. Mirrors how #110
  added `hasActiveBranch`.
- The chip selector's options are the fixed classification set from
  `docs/adapters/TRACKER.md` (`bug`, `enhancement`, `documentation`, `question`), not
  every label found on a loaded issue — matching the Done-when's "scoped to the tag
  values #104 actually produces" (the `initiative` label, for instance, is not a
  classification tag and must not appear as a filter chip).
- Tag filtering is OR-within/AND-across: selecting more than one chip matches an issue
  carrying *any* of the selected tags, and that combines with the existing text/
  hide-shipped/active-branch filters the same way `activeBranchOnly` already does
  (an initiative survives by matching itself or by having a surviving child).

## Deviations / notes
- none
