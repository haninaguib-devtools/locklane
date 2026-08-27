# 178 — Project console page: tab strip for multiple open consoles
Issue: #178 · Part of: #176

## Asked
The project-console page (`project-console.component`) shows one lone `app-terminal` —
its own comment notes there's only ever one session, so no tab strip exists. Now that a
project can have multiple open consoles (#177), give this page a tab strip: one tab per
open console, an "x" to close each, and a "+" to open another — the same interaction
`console-tabs.component` already provides for issue consoles.

## Done when
- The project-console page shows a tab per open console for that project, sourced from
  #177's list endpoint.
- Clicking a tab switches the active terminal; each open console's PTY connection stays
  alive while its tab is hidden (same rule `main-content.component` follows for issue
  consoles today — inactive tabs stay mounted, just hidden).
- An "x" on a tab closes that console; a "+" opens a new one (via #177's mint-id call).
- `client/src/app` build and existing tests pass.

## Explicitly not
- No changes to the sidenav "+" entry point — split to #180.
- No changes to the consoles-list page — split to #179.

## Decisions made along the way
- `ConsoleTabsComponent` grew two inputs, `overview` and `locationChoice` (both
  defaulting to `true`, so the issue pages are untouched): the project page's strip has
  no Overview tab and no main/worktree choice — every project console runs in the
  project's own checkout, so the "where" question doesn't exist there. This is the
  "reuse rather than duplicate" the issue's Scope asks for (agent, 2026-08-27).
- Tab labels on this page are `console`, `console 2`, … plus the agent when this
  browser launched it (`console · claude`), rather than `console-labels.ts`'s
  main/wtree labelling — location carries no information when every console is in the
  same checkout (agent, 2026-08-27).
- On load the page selects the **most recently attached** open console — the same
  console the pre-tab page attached to via `GET /console`, so reattach behavior is
  unchanged for a single-console project (agent, 2026-08-27).
- Closing the last console returns the page to the agent-picker starter, mirroring the
  fresh-project state (agent, 2026-08-27).

## Deviations / notes
- **Scope grew by one file pair:** `client/src/app/services/project-console.service.ts`
  (and its spec). The issue's Scope names only the two component directories, but the
  page reaches #177's list and per-console-close endpoints through this service — the
  established home for the project-console HTTP calls. Its now-unused `find()`
  (`GET /console`) was removed; the tab strip loads via the list endpoint instead.
  Made as the minimal enabler for the done-when; not separately approved in the moment
  (autonomous session) — flagged here and in the PR for the human to confirm at
  review/ship.
- `client/src/app/app.component.spec.ts` — two-line touch: its two project-console
  routing tests stubbed the old `GET /console` call and had to stub the new
  `GET /console/sessions` instead. Read as inherent to the issue's "existing tests
  pass" done-when, not as scope growth.
