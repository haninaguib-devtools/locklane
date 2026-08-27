# 180 — Sidenav: + per project opens a new console; drop 'New issue (agent)' button
Issue: #180 · Part of: #176

## Asked
Add a "+" icon to each project's row in the sidenav that opens a new console for that
project (minting a fresh session id via the engine work from #177), the same one-click
entry point issues already get for their own consoles. In exchange, remove the project
page's "New issue (agent)" button and replace it with a link to the new consoles page
(#179).

## Done when
- Each project row in the sidenav shows a "+" that opens a new console for that
  project, landing on the project-console page's tab strip (#178) with the new
  console's tab active.
- `project-summary.component`'s "New issue (agent)" button is removed; in its place is
  a link to the consoles page (#179) for that project.
- `client/src/app` build and existing tests pass.

## Explicitly not
- No change to the consoles page's own content (#179) or the project-console page's
  tab mechanics (#178) beyond linking/navigating to them.

## Decisions made along the way
- The sidenav "+" is one-click, so there is no agent picker on the way: the minted
  console is tagged with the pickers' shared default agent (`claude`) in AgentStore,
  and lands on `/projects/<id>/console?session=<id>` — the query-param handoff the
  consoles page (#179) already uses and the tab strip (#178) already reads
  (agent, 2026-08-27).
- The "+" shows only on a READY project (a cloning/failed project cannot mint a
  console), mirroring the READY guard the old "New issue (agent)" button had; the
  new consoles-page link keeps that same guard (agent, 2026-08-27).
- A double-click guard ignores further "+" clicks while a mint is in flight; a
  failed mint re-arms the button silently, matching the sidenav's existing action
  handlers (retry/delete), which surface no error UI either (agent, 2026-08-27).
- The replacement link is a real anchor via routerLink (the #174 convention), labeled
  "open consoles", matching the consoles page's own "Open consoles" heading
  (agent, 2026-08-27).

## Deviations / notes
- `client/src/app/app.component.spec.ts` is outside the issue's Scope line but had to
  change: its app-level test clicked the removed "New issue (agent)" button and failed
  once the button was gone. The done-when ("existing tests pass") cannot hold without
  adapting that one test, so it was rewritten to exercise the replacement link
  (summary → consoles page, sidenav still on the project). Needs the human's nod at
  review — flagged in the closing report (agent, 2026-08-27).
- `app.routes.ts` carries a now-stale comment on the `projects/:projectId/console`
  route ("reached from the 'New issue (agent)' button …, never from the sidenav").
  The file is outside this task's scope, so it was left alone — flagged in the
  closing report instead.
