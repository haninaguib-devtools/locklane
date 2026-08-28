# 255 — Clean up sidenav filters: drop active-branch toggle, restyle and rename Open checkbox
Issue: #255

## Asked
In the sidenav's filter row, remove the "active branch" checkbox filter (it isn't
useful), restyle the remaining checkbox so it uses the app's own visual style instead of
the browser's default checkbox appearance, and rename its label from "opened issues" to
"Open".

## Done when
- The "active branch" checkbox/label is removed from `sidenav.component.html`, and its
  `activeBranchOnly` binding and any filtering logic that exists solely to support it (in
  `tree-filter.ts`, including its spec coverage) are removed rather than left dead.
- The remaining checkbox no longer renders as a bare native `<input type="checkbox">` —
  it is restyled to match the app's existing theming (CSS custom properties already used
  in `sidenav.component.css`, e.g. `--border`, `--panel`, `--text`). A human visually
  confirms it reads as part of the app rather than the browser default.
- The remaining checkbox's visible label reads "Open" (was "opened issues").
- `client` test suite passes (`tree-filter.spec.ts` and any sidenav component spec
  updated to match).

## Explicitly not
No changes to other filter behavior (e.g. the text filter input) or to any other
component's checkboxes.

## Decisions made along the way
- none

## Deviations / notes
- none
