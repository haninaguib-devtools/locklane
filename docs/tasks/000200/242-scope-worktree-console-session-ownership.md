# 242 — Scope worktree/console session ownership to the owning project
Issue: #242 · Part of: #236

## Asked
Today, a worktree or console session's `owner_username` is set by whichever
authenticated request attaches to it first ("first-attach-claims-it") — an unclaimed
session is attachable by anyone. That was an acceptable stand-in for a single-tenant
app (#48), but becomes a real cross-user access gap now that projects have real
owners (#239). Replace it: a session's visibility and attach authorization derive
from its owning project's `owner_user_id` (or an admin), not from whoever happens to
attach first.

## Done when
- An authenticated user who is neither the owning project's owner nor an admin is
  rejected when attempting to view or attach to a worktree session — over both the
  REST API and the WebSocket endpoint (`TerminalWebSocketHandler`) — rather than
  being able to see it or silently claim it.
- The existing "first attach claims it" behavior and its test coverage
  (`WebSocketSessionOwnershipIntegrationTest`) are updated to assert the new
  project-owner-derived model instead.
- `./mvnw -B test` passes.

## Explicitly not
- Project ownership itself — this issue depends on it (#239, already merged onto this
  branch).
- No `## Plan` section exists on the issue: its declared Scope (`engine/**`:
  `WorktreeSessionRecord`, session attach/authorization logic,
  `TerminalWebSocketHandler`/`WebSocketConfig`) was independently confirmed, before
  this session started, to touch no path `.t-workflow/scripts/protected-paths.sh`
  currently protects, so no plan-gate applies; the issue's own Scope line is the
  binding boundary per AGENTS.md. Re-confirmed against the actual diff in Phase 3
  (`protected-paths.sh --stdin` over `git diff --name-only` exits 1 — none protected).

## Decisions made along the way
- **One new shared class, `WorktreeSessionAuthorization` (`engine/src/main/java/dev/
  locklane/engine/persistence/WorktreeSessionAuthorization.java`), is the single
  authorization check** for "may this user view/attach to this session id" — used
  identically by the REST listings (`IssueWorktreeService`, `ProjectConsoleService`)
  and by `TerminalWebSocketHandler`'s WebSocket attach. It resolves a session's owning
  project from the leading numeric segment of its id (every real worktree/console id
  is shaped `"<projectId>-..."`, the same convention `SessionRegistry`'s own
  `PROJECT_ID_PREFIX` already relies on for broadcast attribution), loads that
  project via `ProjectRepository`, and checks the caller's `UserRecord` — admin always
  passes; otherwise `project.ownerUserId() == caller.id()`. One implementation means
  the REST and WebSocket paths cannot silently drift into different answers for the
  same session id.
- **`owner_username` on `WorktreeSessionRecord`/the `worktree_sessions` table is kept
  as-is — still stamped on first attach, but now purely informational**, never
  consulted for authorization. Re-deriving it from the project owner's username at
  attach time (as ADR-007 Decision 6's "denormalized record of that derivation"
  literally suggests) was considered and deferred: it would require threading a
  `UserRepository`/`ProjectRepository` lookup into `SessionRegistry.attach` for a
  column nothing reads for a security decision any more, for no behavior change this
  issue's Done-when asks for. Flagged here rather than done silently; a follow-up
  issue can revisit it if the column's meaning turns out to matter for something else
  (e.g. an audit trail).
- **A session id with no resolvable project (doesn't start with `"<digits>-"`), or one
  whose project no longer exists, is visible to nobody but an admin** — there is no
  ownership left to check it against, so the safer default is to show nothing rather
  than everything. This meant the WebSocket integration tests that use arbitrary,
  non-project-prefixed ids purely to exercise terminal I/O (`TerminalWebSocketHandler
  IntegrationTest`, `ProjectConsoleWebSocketIntegrationTest`,
  `WebSocketOriginRestrictionIntegrationTest`'s allowed-origin case) now log in as an
  admin instead of an ordinary account, since they have no real project to be
  authorized against and aren't testing authorization at all.
- **A `null` username is treated as an intentional bypass** (`WorktreeSessionAuthorization
  .isVisibleTo(id, null) == true`), matching the existing "ignore ownership" contract
  `IssueWorktreeService#hasAnySessions`/`#allIssueWorktrees` already had for
  system-level operations. Every real HTTP/WebSocket path passes a real, non-null
  username (`SecurityConfig` requires authentication everywhere upstream), so this is
  only ever reached by internal callers and tests; `TerminalWebSocketHandler` does
  *not* rely on this — a null principal there is treated as unauthorized itself
  (fail closed) before the authorization check is even called, rather than passed in.
- **This is a wider refactor than the three files the issue's Scope line names by
  example** — `IssueWorktreeService`, `ProjectConsoleService`, and every test that
  constructs either, needed the same visibility-model change, since they implement
  the REST half of "authorization logic" the Scope line's `engine/**` covers. Every
  test that asserted "another user's session is invisible" using two different
  attachers *within the same project* had its premise invalidated by the new model
  (visibility is per-project now, uniform for every session in it, regardless of who
  attached) and was rewritten to use two separate projects with two separate owners
  instead — the equivalent case under the new model. None of this coverage was
  deleted; each was replaced with the project-owner-derived equivalent (see the diff
  in `IssueWorktreeServiceTest`, `ProjectConsoleServiceTest`, `ProjectConsoleController
  Test`, `WorktreeControllerTest`, `ConsolesControllerTest`).
- **Test setup exploits the users table's auto-increment id** rather than threading
  real ids through every call site: since `TestSqliteDatabases.newUserRepository`
  points at the same on-disk file as `newProjectRepository`/`newRepository` for a
  given `@TempDir`, creating "alice" first always gives her id `1`, matching every
  pre-existing test's hardcoded `1L` project-owner literal — so tests that never
  exercise authorization at all (worktree creation, the cleanup sweep, project
  deletion) needed no behavior change, only a `WorktreeSessionAuthorization`
  constructor argument (`TestSqliteDatabases.newNoopAuthorization()`, a new test
  helper backed by its own throwaway, never-queried database — safe because every one
  of those call sites passes a `null` requestingUsername, the internal-bypass case
  above).
- **`SessionRegistry.ownerUsername(sessionId)` was removed** — its only caller was
  `TerminalWebSocketHandler`'s now-replaced ownership check, and it was dead once that
  changed.

## Deviations / notes
- `check-blocker-gate.sh`'s equivalent check (native `tracker:list-blockers`) would
  report `#239` as still `OPEN` — expected for a driven-initiative run (ADR-004): its
  merge commit is confirmed part of this branch's history (`git log` shows `fa71097
  [239] Scope projects to owners and move workareas under per-user directories` as an
  ancestor of `HEAD`, which equals `origin/wip/236-integration`), so the actual
  dependency is satisfied even though the tracker issue stays open until the whole
  initiative ships to `main`. Identical situation already noted in #238's and #239's
  own task records.
- **Found and fixed, not part of this issue's own scope, but directly exposed by it:**
  `engine/src/test/resources/application.yml` points every `@SpringBootTest` at a
  fixed on-disk data directory (`${java.io.tmpdir}/locklane-engine-test`), not a fresh
  one per run — harmless under the old ownership model (a session's visibility never
  depended on a user account's *role*, only on a literal username match recorded
  per-session), but a real flakiness risk now that visibility depends on a stored
  `UserRecord.role()`: a username reused across two local `mvn test` runs (or two
  concurrent tasks sharing the same machine, e.g. this task ran alongside #240's) keeps
  whatever role its *first* run gave it, since the test helper only creates an account
  if none exists yet. Hit this directly while validating `WebSocketSessionOwnership
  IntegrationTest`: a stale non-admin row from an earlier run of this same test file
  made a should-be-rejected attach appear to succeed. Fixed within this task's own new
  test (unique per-run username suffixes in `WebSocketSessionOwnershipIntegrationTest`)
  rather than touching the shared `AuthenticatedWebSocketClients` helper's long-standing
  contract for every other consumer — flagging the general fragility here as a
  possible follow-up (e.g. a per-suite-run unique data-dir) rather than fixing it
  unprompted, since it is pre-existing test infrastructure outside this issue's scope.

## Checks
- `./mvnw -B test` — 414 tests, 0 failures, 0 errors. Run twice in a row (including
  once from a clean `/tmp/locklane-engine-test`) to confirm the fix above actually
  holds and the suite is not merely passing by accident of leftover state.
- `./.t-workflow/scripts/consistency-check.sh` — `OK: all consistency checks passed`.
- `git -c core.quotePath=false diff --cached --name-only | bash .t-workflow/scripts/
  protected-paths.sh --stdin` — exit 1 (no protected path touched), confirming the
  Scope line's own pre-check against the real diff.
