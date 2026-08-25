# 15 — Map worktree sessions to their issue and expose them via an endpoint
Issue: #15 · Part of: #1

## Asked
Add an endpoint (e.g. `GET /api/issues/{number}/worktrees`) that lists the known
worktree ids for a given issue, so the client (#3) can render its worktree-tabs row.
The engine had no concept of "which issue does this worktree belong to" — a worktree
id is just an opaque string a WebSocket client supplies (`/ws/sessions/{worktreeId}`,
#7).

## Done when
- `GET /api/issues/{number}/worktrees` returns the worktree ids known for that issue
  (empty list if none), derived from persisted/live session state
  (`WorktreeSessionRepository` / `SessionRegistry`).
- A worktree id that does not match the expected naming convention is handled
  explicitly (excluded, or a documented fallback) rather than throwing.

## Explicitly not
- Any client-side change — that's #3.
- Actually creating/starting worktrees on demand from the API — this only reports
  what already exists.

## Decisions made along the way
- Confirmed the issue's own suggested assumption before building on it: nothing in
  the codebase enforces any naming convention on a worktree id — it's genuinely
  whatever string a WebSocket client happens to supply (`TerminalWebSocketHandler`,
  #7). So this task defines the convention rather than merely following an existing
  one: a worktree id shaped `<issueNumber>-<slug>` belongs to that issue — the same
  shape as the `wip/<id>-<slug>` branch name (`AGENTS.md`) and the
  `../<repo-name>-<id>` directory `/t-wtree` creates, with the repo-name/branch
  prefix stripped, since stripping it is what a WebSocket client actually has to do
  to name a session. `"main"` and any id not starting with a number match no issue
  and are simply excluded, never thrown on (haninaguib, 2026-08-25).
- Used the digit-prefix regex `^(\d+)-` rather than a numeric string-prefix check, so
  issue 174 never falsely captures a worktree belonging to issue 1740 — added a
  regression test for exactly this
  (`doesNotFalselyMatchAnIssueNumberThatIsAPrefixOfAnother`) (haninaguib, 2026-08-25).
- Placed `IssueWorktreeService`/`WorktreeController` in
  `dev.locklane.engine.persistence` (alongside `WorktreeSessionRepository`, from #6)
  rather than the `github` package that hosts `IssueController` — this is a
  session/persistence concern, not a GitHub-data one, even though the URL path
  (`/api/issues/{number}/worktrees`) nests under the same prefix. Spring resolves the
  routes across the two controllers without conflict (haninaguib, 2026-08-25).
- Filters `repository.findAll()` in Java rather than adding a parameterized SQL
  query, matching the established pattern in `GhIssueCache.pullRequestForIssue`
  (#16) — the session count is small enough (Phase 0 solo use) that this is not a
  real cost (haninaguib, 2026-08-25).
- Manually verified the real Spring + SQLite wiring end to end (not just the
  TestSqliteDatabases-backed unit tests): started the app, confirmed
  `/api/issues/15/worktrees` returns `[]` before anything is recorded, inserted a
  matching row directly into the real `~/.locklane/locklane.db`, confirmed it then
  appears for issue 15 and correctly does not appear for an unrelated issue number,
  then cleaned up the inserted row (haninaguib, 2026-08-25).

## Deviations / notes
- none
