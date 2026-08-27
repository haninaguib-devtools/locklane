# 179 — Consoles page: list a project's open consoles
Issue: #179 · Part of: #176

## Asked
No page today shows what consoles are currently open for a project. Add one, backed by
#177's list endpoint (`GET /api/projects/{projectId}/console/sessions`), so a project's
open consoles are visible and reachable in one place — this is also the page the
sidenav "+" and the removed "New issue (agent)" button (#180) will point to.

## Done when
- A new route/page lists a project's open consoles (at minimum: which one, when it was
  started), reading from #177's endpoint.
- Selecting one from the list opens it in the project-console page's tab strip (#178),
  reattaching to an already-open console rather than starting a new one.
- `client/src/app` build and existing tests pass.

## Explicitly not
- No change to the project-console page's tab strip itself — split to #178.
- No change to the sidenav entry point beyond linking to this page — split to #180.

## Decisions made along the way
- Selection hands off via the URL: the page navigates to
  `/projects/:projectId/console?session=<sessionId>`. The existing console route
  already matches that URL, and #178's tab strip is the component that reads the
  `session` query param and activates that console's tab — putting the contract in the
  URL keeps this task off `project-console.component`, which #178 owns. Until #178
  lands, the console page ignores the param and shows the most recently attached
  console as it does today; it still reattaches, never starts a new one. (agent,
  2026-08-27)
- "The routing needed to reach it" includes `app.component.ts`/`.html`: routing here is
  component-less (the routes file renders nothing), so a page is only reachable by
  AppComponent recognizing its route segment and rendering it — same wiring the
  project-console page (#140) needed. (agent, 2026-08-27)

## Deviations / notes
- none
