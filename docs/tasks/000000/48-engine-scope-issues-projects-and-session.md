# 48 — Engine: Scope issues, projects, and sessions per authenticated user
Issue: #48 · Part of: #46

## Asked
With users and login in place (#47), the engine's data must actually be scoped by
user: console sessions, and — once #41 lands — projects, are all effectively global
today. Every read/write path needs to attribute data to the authenticated user and
filter by it, so one user cannot see or act on another's issues, sessions, or projects.

## Done when
- Console sessions (SessionRegistry / SQLite session state) are associated with the
  user that created them, and a user cannot attach to another user's session.
- Any per-user GitHub/tracker credentials or configuration (if applicable) are stored
  and read per user rather than as a single global config.
- REST endpoints that list or act on issues/sessions/projects filter by the
  authenticated user rather than returning everything.
- `./mvnw -B test` passes.

## Explicitly not
- The User entity and login mechanism itself (#47).
- The client UI implications of per-user scoping — split to #49, if UI changes are
  needed beyond what the API scoping already forces.
- **Per-user GitHub credential/issue scoping** — deferred, discussed directly with the
  human (2026-08-25). Today issues/PRs come from one shared repo via one global `gh`
  CLI call (`dev.locklane.engine.github.GhClient`); there is no per-project or
  per-user GitHub identity to scope by. The human's own suggestion — an encrypted
  per-project GitHub token (using #47's encryption key), attached to the eventual
  Project entity — depends on #42 (Project entity, part of #41, not started). I'm
  recommending a follow-up issue for this (title: "Store an encrypted per-project
  GitHub token and scope issue/PR fetches through it"), `Part of: #41`,
  `Blocked-by: #42`, for the human to open when #41 resumes.

## Decisions made along the way
- Scope narrowed to session ownership only, per the human's explicit go-ahead
  (2026-08-25) — see "Explicitly not" above for why the issue-scoping bullet can't be
  done yet.
- `worktree_sessions` gets a new nullable `owner_username` column. Stamped only on a
  session's first attach (`WorktreeSessionRepository.recordAttach` — a reattach never
  overwrites it); `NULL` means "unclaimed" (created before this column existed, or by
  an unauthenticated attach — still possible until #50 requires auth on the WebSocket
  endpoint itself) and is treated as visible/attachable by any authenticated user,
  not orphaned.
- Enforcement point: `TerminalWebSocketHandler.afterConnectionEstablished` — before
  attaching, compares the connecting principal's username (`wsSession.getPrincipal()`,
  null if unauthenticated) against the session's recorded owner; a mismatch closes the
  connection with `CloseStatus.POLICY_VIOLATION`. This is authorization, not
  authentication — an anonymous connection is still allowed to attach to an unclaimed
  session, exactly as before this task, since requiring authentication on the endpoint
  itself is #50's job.
- `GET`/`POST /api/issues/{number}/worktrees` (`WorktreeController`) now require
  authentication (`SecurityConfig`) — the only REST endpoints gated in this task, since
  they're the only ones with a per-user meaning today. `IssueController`'s endpoints
  (issue list/tree/detail) stay open: nothing to scope there yet (see "Explicitly not").
  The GET endpoint filters to the caller's own sessions plus unclaimed ones
  (`IssueWorktreeService.worktreeIdsForIssue`); the POST endpoint's "reuse an existing
  session" check (`WorktreeCreationService.startSession`) uses the same filter, so a
  second user starting a session for an issue another user already has one on gets a
  distinct worktree id whenever the underlying worktree directory doesn't already
  exist — see next point for the one case where it doesn't.
- **Known limitation, not fixed here**: a real `git worktree` session's path and id
  are still derived only from the issue number/title, not the user
  (`WorktreeCreationService`), so if two different users both request a *worktree*
  (not main-checkout) session for the *same* issue, the second user's `POST` reuses
  the first user's already-created directory/id — the REST call succeeds, but the
  follow-up WebSocket attach is then correctly rejected by the ownership check above.
  Secure, but a confusing UX rough edge. Fixing it well means deciding whether a
  worktree is per-issue or per-issue-per-user, which is a bigger design question than
  this task's session-ownership scope — flagging it rather than deciding it here.
- `authorizeHttpRequests()` gating a real cookie-authenticated, state-changing endpoint
  (`POST /api/issues/*/worktrees`) for the first time is exactly the trigger #47's
  record named for revisiting CSRF — but a real fix (a cookie-based CSRF token an
  Angular interceptor reads and sends back) is client work outside this task's
  `engine/**` scope. CSRF stays disabled; noted again here as carried forward, for
  #49 to pick up.

## Deviations / notes
- none
