# 963 — Add a Labels menu item to assign issue labels in the sidenav
Issue: #963 · Part of: #961

## Asked
Add a "Labels" item next to "Pin" in each sidenav row's kebab menu. Clicking it opens a popup styled like the existing read-only label filter picker in `sidenav.component.html`/`.ts`, but listing every repo label (fetched from the engine's new list-labels endpoint) with a checkbox pre-checked for each label the row's issue currently carries. Toggling a checkbox calls the engine's new set-labels endpoint to add/remove that label on GitHub and updates the row's displayed labels on success.

## Done when
- The kebab menu shows a "Labels" item next to "Pin" on issue/initiative rows.
- Clicking it opens a popup listing every repo label with checkboxes reflecting the row's current labels.
- Toggling a checkbox calls the engine to add/remove that label on GitHub, and the row's shown labels update on success.
- Covered by a client test exercising the toggle behavior.

## Explicitly not
- Adding/deleting labels themselves — that stays on GitHub.
- Bulk-editing labels across multiple issues at once.
- Changes to the existing read-only label filter picker's own behavior.

## Decisions made along the way
- Scope widened beyond the issue's literal `client/src/app/components/sidenav/`
  to also touch `client/src/app/models/issue.model.ts` (new `GhLabel` interface)
  and `client/src/app/services/issues.service.ts` (`labels()`/`updateLabels()`),
  formalized via `/t-plan 963` before implementation — see that issue's `## Plan`.
- The per-row popup fetches the repo's label list live on every open (no client
  cache), matching the engine's own uncached endpoint (#962).
- No color swatch per label: the engine's `GhLabel.color` isn't used client-side.
  `sidenav.component.css` was already right at its 8kB per-component style
  budget (`anyComponentStyle` in `angular.json`) before this task — confirmed by
  building the pre-existing file alone (7.99kB) — so there was effectively no
  CSS headroom for decoration. Also dropped a `.picker-option.pending` opacity
  rule for the same reason; the in-flight guard still works via the checkbox's
  `[disabled]` binding, just without a dimmed look.
- New popup state (`labelAssignFor`, `repoLabels`, `toggleIssueLabel`, etc.) is
  named distinctly from the existing read-only filter picker's `labels`/
  `filterLabels`/`toggleLabel` so the two don't collide; opening either one
  closes the other and the kebab menu (mutual exclusion via `closeMenu`,
  `togglePicker`, `toggleMenu`, `openLabelAssign` all clearing each other's state).

## Deviations / notes
- none

## Agents
- work: claude-code / claude-sonnet-5
- plan: claude-code / claude-sonnet-5
