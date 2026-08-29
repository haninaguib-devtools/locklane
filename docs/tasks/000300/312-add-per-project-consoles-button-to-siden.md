# 312 — Add per-project consoles button to sidenav
Issue: #312

## Asked
In the sidenav's project row, add a small square, thin-bordered button immediately to
the left of the existing "+" (new console) button. It's visible only when that project
has open consoles, shows the same blue-solid / pulsating-amber dot the app already uses
for "consoles open" / "waiting for attention" (scoped to that project), and clicking it
navigates to that project's console page.

## Done when
- A new square, thin-bordered icon button renders immediately to the left of
  `.new-console` in each project's `.section-header` row.
- The button is hidden entirely when the project has no open consoles, and appears when
  it does.
- The dot inside the button is solid when the project has open consoles with none
  waiting, and switches to the existing pulsating-amber waiting state when any of that
  project's open consoles are waiting for attention.
- Clicking the button navigates to `/projects/:projectId/console`.
- The existing "+" and pop-out buttons keep their current position and behavior.
- `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No changes to the header `console-indicator` widget's own logic or styling.
- No changes to what counts as "waiting for attention" (`ConsoleAttentionEvent`
  semantics) — this task only surfaces existing state in a new place.

## Decisions made along the way
- The project-scoped `hasOpenConsoleForProject`/`hasAttentionWaitingForProject` checks
  are derived from the sidenav's existing `openConsoleIssues`/`waitingIssues` sets
  (prefix-matched on `<projectId>:`) rather than adding any new tracked state, per the
  issue's "reusing state... rather than new state" instruction. This means a
  project-level console with no issue attached (opened via the project's own "+") is
  not counted, matching the same scope the row-level `console-dot` already has today —
  neither `openConsoleIssues` nor `waitingIssues` track those either (haninaguib,
  2026-08-29).

## Deviations / notes
- none
