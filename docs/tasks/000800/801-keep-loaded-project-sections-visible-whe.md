# 801 — Keep loaded project sections visible when a sidenav refresh's project-list request fails
Issue: #801

## Asked
When the sidenav refreshes and the project-list request itself fails, the projects
that were already on screen vanish behind the sidenav-wide "could not load issues"
message until a later refresh succeeds. Keep the already-loaded project sections
visible and usable through such a failure, and report the failed refresh in a way
that does not replace them — the same way a failed request for one project's tree
already degrades to a per-project notice since #787.

## Done when
- With projects already rendered, a `refresh()` whose `/api/projects` request fails
  leaves every existing project section in the DOM, rows included, and
  `mainNodesFor` on each section still returns what it did before the refresh.
- The failure is still visible: the sidenav shows a non-modal refresh-failed notice
  while the old sections stay below it, and the notice clears on the next
  successful list load.
- The initial load, where nothing has been rendered yet, still shows the
  sidenav-wide `could not load issues` state when the list request fails.
- `refreshing` still returns to `false` after the failed request, a queued refresh
  (#738) still runs after it, and a pending reveal (#717) is still released on a
  failed list load exactly as today.
- Client specs cover: a failed list refresh keeps the existing sections and rows;
  the notice appears and then clears on the next successful load; the first load's
  failure state is unchanged.

## Explicitly not
- Changing how the engine serves or retries the project list.
- Changing the per-project tree failure state #787 introduced.
- Any change outside the sidenav component.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- Distinguished "initial load never having rendered anything" from "a later
  refresh's list request failed" with a new `hasLoadedList` flag, set once the
  first successful list load lands and never reset. The existing `error` field
  keeps its original meaning (sidenav-wide, first-load-only) and a new
  `listRefreshFailed` field covers the new degrade-in-place notice — reusing
  `error` for both would have meant either weakening the first-load state's own
  test coverage or teaching the template two different reasons for the same flag
  (haninaguib, 2026-09-07).
- Styled `.list-refresh-error` identically to the existing `.github-error` banner
  (border, color, sizing) rather than inventing a new visual language, since both
  are non-modal, non-dismissable failure notices scoped to a subtree that keeps
  showing stale-but-real data underneath (haninaguib, 2026-09-07).

## Deviations / notes
none
