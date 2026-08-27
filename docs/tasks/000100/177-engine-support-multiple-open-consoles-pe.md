# 177 — Engine: support multiple open consoles per project
Issue: #177 · Part of: #176

## Asked
`ProjectConsoleService` mints exactly one deterministic session id per project
(`<projectId>-console`, enforced by `^(\d+)-console$`), so a project can only ever have
one open console. Change that to a family of ids per project — the same shape issue
consoles already use (many ids per issue) — so a project can have several open consoles
at once, and add an endpoint that lists a project's currently-open consoles.

## Done when
- A new "open a console for this project" call mints a fresh session id in that
  project's id family, rather than reusing the same fixed id every time.
- A new endpoint lists the open consoles for a project (id, started time — whatever
  the client-side consoles page (#179) needs at minimum).
- `environmentFor`'s regex and `IssueWorktreeService`'s exclusion logic (which assume
  the old single `^(\d+)-console$` shape) are updated to match the new id family
  without colliding with issue session ids.
- Existing single-console behavior (open, reattach, close) keeps working for a project
  with just one open console.
- `./mvnw -B test` passes.

## Explicitly not
- No client-side changes — the tab strip (#178), consoles page (#179), and sidenav
  wiring (#180) are separate child tasks that build on this endpoint.

## Decisions made along the way
- Id family is `<projectId>-console-<8-hex>` (the same `shortId()` convention
  `WorktreeCreationService` uses for `-main-`/`-resume-` ids), and the legacy bare
  `<projectId>-console` remains a recognized member — a console opened before this
  change keeps reattaching, resolving `GH_TOKEN`, and closing exactly as before
  (agent, 2026-08-27).
- The family never collides with issue session ids by construction: issue ids start
  with two numeric segments (`^(\d+)-(\d+)-`), while the family's second segment is
  the literal `console` — so `IssueWorktreeService`'s existing prefix regex already
  excludes project consoles from every issue/picker list; verified by new tests
  rather than changed (agent, 2026-08-27).
- The list endpoint is `GET /api/projects/{projectId}/console/sessions` (the sibling
  `/api/projects/{projectId}/consoles` was already taken by #32's cross-issue console
  list). Rows carry `sessionId`, `workingDirectory`, `createdAt`, `lastAttachedAt`,
  ordered oldest-first for a stable tab order in #178 (agent, 2026-08-27).
- "Open" means "has a persisted session record" — the same notion `find`/`close`
  already used: an explicit close deletes the record, a mere disconnect does not
  (agent, 2026-08-27).
- `DELETE /console/{sessionId}` (per-console close) added alongside the family: the
  #178 tab strip's per-tab "x" has no way to name which console to close through the
  old bare `DELETE /console`, which stays and now closes the same console `GET
  /console` reports — the most recently attached one — preserving single-console
  behavior (agent, 2026-08-27).

## Deviations / notes
- **Scope grew by one file:** `engine/src/main/java/dev/locklane/engine/security/SecurityConfig.java`.
  The issue's Scope names only the controller, the service, and
  `IssueWorktreeService`, but the security chain authenticates by exact path
  (`/api/projects/*/console`) and falls through to `permitAll` — so the new
  sub-path endpoints (`GET /console/sessions`, `DELETE /console/{sessionId}`)
  would have shipped unauthenticated without one added matcher line
  (`/api/projects/*/console/*`). Made as the minimal enabler for the task's own
  done-when rather than shipping an open endpoint; not separately approved in the
  moment (autonomous session) — flagged here and in the PR for the human to
  confirm at review/ship.
- Test files for the three in-scope classes were updated alongside them
  (`ProjectConsoleServiceTest`, `ProjectConsoleControllerTest`,
  `IssueWorktreeServiceTest`) — read as inherent to the issue's "`./mvnw -B test`
  passes" done-when, not as scope growth.
