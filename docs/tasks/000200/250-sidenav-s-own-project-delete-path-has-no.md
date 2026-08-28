# 250 — Sidenav's own project-delete path has no error handling
Issue: #250

## Asked
The sidenav has its own delete-project action, shown only for projects in the `FAILED`
state (`sidenav.component.ts` `deleteProject()`/`confirmDeleteProject()`). Its subscribe
call has no `error` callback: `this.projectsService.delete(projectId).subscribe(() =>
this.refresh())`. If the delete request fails (e.g. a 409 `HAS_OPEN_SESSIONS`, or a
network error), nothing happens — no error is shown to the user and the list simply
doesn't update, with no indication of why.

## Done when
- A failed delete from the sidenav's FAILED-project delete action surfaces a
  user-visible error (consistent in style with how ProjectSummaryComponent shows its
  `HAS_OPEN_SESSIONS` inline error).
- A successful delete still refreshes the sidenav as it does today.

## Explicitly not
- none

## Decisions made along the way
- Mirrors `ProjectSummaryComponent.confirmDelete()`'s `error` callback
  (`err.error?.error ?? 'could not delete this project'`) and its `.delete-error` inline
  message style. Tracks which project the error belongs to
  (`deleteErrorProjectId`/`deleteError`) since the sidenav can list more than one FAILED
  project at once, and clears it when a new delete confirm is opened. (haninaguib,
  2026-08-27)

## Deviations / notes
- none
