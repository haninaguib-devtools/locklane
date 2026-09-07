# 752 — Move past sessions from project console page to project page
Issue: #752

## Asked
On the project page, a user should be able to see and reopen this project's past
console conversations without first opening a live console — the same way they can
already do it from an issue's Overview tab. Today that list only exists on the project
console page, tucked behind a "past sessions" disclosure under a "Project console"
heading; a user has to start (or already have) a console open just to find it. This
moves the list to the project page, displayed the same way the issue Overview tab
already shows it — as an always-visible column on the right of the page — and removes
it, along with the now-redundant "Project console" heading, from the project console
page.

## Done when
- The project page (`ProjectSummaryComponent`) shows a right-hand column of this
  project's past console sessions, laid out the same way `OverviewTabComponent`'s
  `sessions-rail` aside is: a `main-col` + `aside.sessions-rail` row,
  `h3.sessions-title` reading "past sessions", `app-session-list` inside.
- The list is fetched via `ProjectConsoleService.resumeSessions(projectId)`, loaded
  with the rest of the page rather than behind a disclosure.
- Clicking "reopen" calls `ProjectConsoleService.reopenSession(projectId,
  worktreeId)` and then navigates to the project's console page with the newly-started
  session selected, the same `router.navigate` pattern `onConsoleButtonClick` already
  uses for "Open console".
- `ProjectConsoleComponent`'s template no longer has the `<h1>Project console</h1>`
  title, nor the "past sessions" toggle/disclosure and its state.
- `project-console.component.spec.ts` no longer asserts on the removed title/disclosure;
  `project-summary.component.spec.ts` covers the new past-sessions column (empty state,
  loading, reopen).
- `./mvnw -B test` passes and `./.t-workflow/scripts/consistency-check.sh` passes.

## Explicitly not
- No change to `OverviewTabComponent`/the issue page's own sessions rail — none.
- No change to how sessions are fetched or reopened on the engine side, or to
  `ResumeSession`'s shape — none.

## Decisions made along the way
- Reopening a past conversation now happens on a page (`ProjectSummaryComponent`) that
  never mounts a `<app-terminal>` itself — the actual resume launch
  (`<tool> --resume <id>`) only happens once the browser lands on the project console
  page and that page's own first WebSocket attach fires. Carried `resume`/`tool`
  through the same `router.navigate` query params the session id already travels in
  (`?session=&resume=&tool=`), and had `ProjectConsoleComponent.loadConsoles()` apply
  them to the one matching session — otherwise the reopened console would silently
  launch a blank conversation instead of resuming the captured one. Both touched files
  are within the issue's own declared Scope, so no scope expansion. (Claude, 2026-09-07)

## Deviations / notes
- Touched `client/src/app/app.component.spec.ts` outside the issue's declared Scope
  (`client/src/app/components/project-summary/`, `client/src/app/components/project-console/`),
  test-only: `flushProjectConsoleSessions()`'s existing helper under-flushed the
  project summary's new `console/resume-sessions` fetch across 24 `AppComponent` specs
  that mount the summary. Extended that one helper to also flush the new endpoint,
  mirroring the same test-only ride-along #745's record already used for this exact
  helper file.
