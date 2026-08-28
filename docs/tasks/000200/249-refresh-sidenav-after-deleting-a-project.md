# 249 — Refresh sidenav after deleting a project from its detail page
Issue: #249

## Asked
Deleting a project from its own summary/detail page navigates the user back to the
overview, but the sidenav (the project list on the left) has no way to learn the
project is gone. It keeps showing a stale link to the deleted project's id until
something unrelated refreshes it, and clicking that stale link re-navigates into a
route for a project that no longer exists.

## Done when
- Deleting a project from the project summary/detail page removes that project's entry
  from the sidenav immediately, with no stale `routerLink` left pointing at the deleted
  project's id.
- No full page reload is required to see the sidenav update.
- The existing delete flow on the summary page (confirm dialog, navigate to `/` on
  success, inline error message on the `HAS_OPEN_SESSIONS` 409) is unchanged.

## Explicitly not
- The sidenav's own delete path (`sidenav.component.ts` `deleteProject()` /
  `confirmDeleteProject()`, only shown for `FAILED` projects) has no error handling at
  all today — a failed delete there fails silently. Pre-existing gap, left alone here.

## Decisions made along the way
- Mirrors the existing project-creation wiring (`AppComponent.onProjectCreated()` calls
  `sidenav?.refresh()`): add a `projectDeleted` output on `ProjectSummaryComponent`,
  emitted on successful delete, and a matching `onProjectDeleted()` handler on
  `AppComponent` that calls `sidenav?.refresh()`. (haninaguib, 2026-08-27)

## Deviations / notes
- none
