# 745 — Add 'Open shells' button to project summary
Issue: #745

## Asked
On a project's summary page, add an "Open shells" button next to the existing "Open
console(s)" button. Clicking it opens the singleton Shells popup window
(`window.open('/shells/...', 'locklane-shells')`) scoped to this project: if the
project already has an open shell, it opens/focuses that window on the most recently
used one of those shells; if the project has none, it first mints a new shell at the
project's main worktree (`issueNumber: null`, `workingDirectory` = the project's
`workareaPath`) and then opens/focuses the window on that new shell. Mirrors
`onConsoleButtonClick()`'s reuse-most-recent-or-create shape, applied to shells.

## Done when
- `project-summary.component.html` renders an "Open shells" button beside the console
  button, visible under the same conditions as the console button.
- Clicking it when the project has one or more open shells opens/focuses `/shells` on
  the most recently used one — no new shell is minted.
- Clicking it when the project has none mints exactly one new shell at the project's
  main worktree and then opens/focuses `/shells` on it.
- A failed mint surfaces an inline error on the project summary and never opens a
  stale or empty window.
- Unit tests cover both branches in `project-summary.component.spec.ts`.
- `./mvnw -B test` passes.

## Explicitly not
- No change to the Shells window's own layout, grouping, or selection UI — none.
- No backend/API change — none.

## Decisions made along the way
- none

## Deviations / notes
- none
