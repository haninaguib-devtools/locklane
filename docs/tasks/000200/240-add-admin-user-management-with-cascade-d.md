# 240 — Add admin user management with cascade delete

Issue: #240 · Part of: #236

## Asked

Give the admin account a way to create and remove other accounts — there is no
self-registration, so this is the only way a second user ever gets access. Creating a
user sets `must_change_password = true` (#241's gate then forces the change on first
login). Deleting a user removes everything only they had: their user row, their owned
projects, those projects' on-disk workarea checkouts, and any worktree/console sessions
tied to those projects.

## Done when

- Admin-only endpoints exist to create a user (username + admin-set or generated
  temporary password) and to delete a user by id; a non-admin caller gets 403.
- Creating a user sets `must_change_password = true` on the new row.
- Deleting a user cascade-deletes: the user's owned projects, those projects' on-disk
  workarea directories, and any `worktree_sessions` rows scoped to those projects.
- A client admin panel lists existing users and exposes create/delete, visible only to
  an admin account.
- `./mvnw -B test` passes; client tests pass.

## Explicitly not

- The self-service password-change endpoint and the forced-change-on-first-login gate
  itself — #241 (already merged onto this branch).
- Worktree/console session *ownership* semantics — #242 (running concurrently, on its
  own worktree, on top of the same integration branch). This task deletes
  `worktree_sessions` rows scoped to a deleted user's projects purely at the
  row/repository level (matching by worktree-id prefix, the same convention
  `IssueWorktreeService.hasAnySessions` already uses) — it never touches
  `WorktreeSessionRecord.java`, `TerminalWebSocketHandler.java`, or
  `WebSocketConfig.java`, which is #242's surface.
- No `## Plan` section exists on the issue: its declared Scope (`engine/**`: an admin
  controller, the cascade-delete service; `client/src/app/**`: an admin user-management
  view/panel) was independently confirmed (per the driving session's setup) to touch no
  path `.t-workflow/scripts/protected-paths.sh` currently protects — confirmed again
  directly against every touched main-source file before implementation
  (`./.t-workflow/scripts/protected-paths.sh <files>` exits 1 — none protected).

## Decisions made along the way

- **Reused #239's existing single-project delete machinery rather than writing a second
  copy of it.** `ProjectCheckoutService` gained `forceDelete(long id)` — the same
  DB-row-plus-workarea-directory removal `delete(id)` already does, minus that method's
  refusal (#231) when an open worktree/console session exists. Cascade-deleting a user
  is exactly the case where those sessions are supposed to disappear along with the
  project, not block the delete, so `forceDelete` calls a new
  `IssueWorktreeService.deleteSessionsForProject(long projectId)` (reusing the same
  worktree-id-prefix matching `hasAnySessions` already has) to remove them first, then
  deletes the project row, then best-effort removes the workarea directory — same order
  and same best-effort semantics `delete()` already uses.
- **`UserCascadeDeleteService` is a thin orchestrator, not a copy of `forceDelete`'s
  logic**: it lists the user's owned projects (`ProjectRepository.findAllOwnedBy`) and
  calls `ProjectCheckoutService.forceDelete` once per project. It never touches the
  `users` table itself — `AdminUserController.delete` calls it, then deletes the user row
  last, once every owned project is confirmed gone.
- **No database transaction wraps the cascade** — nothing in this codebase uses one
  (no `PlatformTransactionManager` is wired up anywhere; confirmed by grep). Instead the
  order of operations is what limits the blast radius of a partial failure: each owned
  project is deleted in full (its sessions, its own row, then its on-disk checkout)
  before moving to the next, and the user row is deleted only after every project has
  been processed. A failure partway through (e.g. a DB error deleting project N of M)
  leaves the already-deleted projects gone, the rest of the projects and the user row
  untouched, and no orphaned `worktree_sessions` rows or workarea directories for
  whatever *did* finish — because each project's sessions/row/directory are removed
  together, in that order, not split across a later step. Retrying the same delete
  call afterwards is safe and resumes correctly: `deleteEverythingOwnedBy` re-lists
  whatever the user still owns, and `forceDelete`/`deleteSessionsForProject` are no-ops
  against a project or session that is already gone.
- **The temporary password**: `AdminUserController.create` generates one (24 bytes of
  `SecureRandom`, URL-safe base64) only when the admin leaves `password` blank/omitted;
  it is BCrypt-hashed before being stored and returned in the `POST` response body
  exactly once (`{"user": {...}, "temporaryPassword": "..."}`) — the only moment it can
  be handed to the admin to pass to the new account holder, since it is never stored or
  logged in the clear afterward. An admin-supplied password is used as-is and never
  echoed back (the admin already knows it). Every account created here defaults to role
  `USER`; this endpoint has no way to create a second admin.
- **Admin-only gating is server-side, via a new `SecurityConfig` route matcher**
  (`.requestMatchers("/api/admin/**").hasRole("ADMIN")`), not just hidden client-side.
  `hasRole` implies authentication, so an unauthenticated caller still gets 401 from the
  same entry point as everything else; an authenticated non-admin gets Spring Security's
  own 403 before `AdminUserController`'s method body ever runs. This is the first
  `hasRole`-based matcher in `SecurityConfig` — every existing gate there is a plain
  `authenticated()`, with 403s constructed by hand inside controller bodies (e.g.
  `AccountPasswordController`) — so a new integration test class
  (`AdminUserRouteIntegrationTest`) exercises the real filter chain end-to-end
  (401/403/200) rather than trusting only the controller-level unit tests.
- **`/api/auth/me` (and every endpoint that establishes a session — the plain-login
  success path, `/api/auth/2fa/verify`, `/api/auth/password/change`) now returns
  `role` alongside `username`.** The client's admin panel needs to know the signed-in
  account's role to gate its own visibility, and the instruction was to extend the
  existing "who am I" surface rather than add a new one. Returning it from every
  session-establishing response (not just `/api/auth/me`) was needed to avoid an extra
  round trip after login: `AuthService`'s existing tests assert `isLoggedIn`/`username`
  flip synchronously off the login/verify/change response body alone (no follow-up
  `checkSession()` call), so adding a background refetch there would have required
  rewriting that whole test file's request-count expectations for no benefit.
  `TwoFactorAwareLoginSuccessHandler`'s plain-success branch previously sent no body at
  all (just `200`); it now sends `{"username", "role"}` when the account row is found,
  otherwise still just the bare `200` for the (should-never-happen) case it's missing.
- **`role` is a display-only signal client-side, not an authorization check.**
  `AuthService.isAdmin` (`computed(() => this.userRole() === 'ADMIN')`) only decides
  whether `AppComponent` renders the "Manage users" menu item and the panel it opens —
  every `/api/admin/**` request is independently enforced server-side regardless of what
  this signal believes, exactly like every other admin-only surface in this app.
- **The admin panel is a new dialog (`AdminUsersComponent`), not a route.** This app's
  routing is component-less (`AppComponent` renders based on the URL's project/issue
  segments directly; see `app.routes.ts`), and every other account-level surface
  (settings, add-project) is already a full-page-backdrop popup opened from
  `AppComponent` state rather than a route — `AdminUsersComponent` follows that same
  visual and structural pattern (`settings-dialog` was the closest precedent), opened
  from a new "Manage users" item in the header's account menu, gated on
  `AuthService.isAdmin()`.
- **Deleting an account goes through `ConfirmDialogComponent`**, the same
  are-you-sure prompt every other destructive action in this app already uses (project
  delete, closing a console) — deleting a user is irreversible and takes projects,
  checkouts, and sessions with it, which the confirm message says explicitly.
- **An admin cannot delete their own account** (`AdminUserController.delete` returns 409
  when the target's username matches the caller's) — not asked for explicitly, but a
  small, self-contained guard against locking the caller out of the very account making
  the request; the client hides the delete button for the signed-in account's own row
  for the same reason, though the server-side check is what actually matters. No guard
  exists against deleting the *last* admin — out of scope, since it would need extra
  querying this issue's done-when doesn't ask for and there is always at least the
  original bootstrap admin unless it is the one being deleted.
- **`UserRepository` grew `findById`, `findAll`, `deleteById`, and a 5-argument
  `create(username, passwordHash, now, role, mustChangePassword)` overload** — the
  existing 3- and 4-argument overloads are unchanged and now just delegate with
  `mustChangePassword = false`, so #238's and #241's existing callers/tests needed no
  changes.

## Deviations / notes

- `check-blocker-gate.sh` reports both `#238` and `#239` as still `OPEN` on the
  tracker — expected for a driven-initiative run (ADR-004): both issues' merge commits
  (`4ada5d6`, `fa71097`) are confirmed as ancestors of this branch's `HEAD`
  (`git merge-base --is-ancestor`), so the actual dependency is satisfied even though
  the tracker issues stay open until the whole initiative ships to `main`. Identical
  situation already noted in #239's own task record.
- Extended, rather than left alone, five existing engine test files
  (`AuthControllerIntegrationTest`, `LoginIntegrationTest`, `TwoFactorLoginIntegrationTest`,
  `ForcedPasswordChangeLoginIntegrationTest`) with one additional `jsonPath("$.role")`
  assertion each, and `IssueWorktreeServiceTest`/`ProjectCheckoutServiceTest`/
  `UserRepositoryTest` with new-method coverage — all directly exercise behavior this
  task added or changed on files already in scope, not drive-by changes.
- `AdminUserRouteIntegrationTest` creates one throwaway non-admin account
  (`admin-route-test-ordinary-user`) against the shared test SQLite database
  `@SpringBootTest` integration tests reuse across the whole test run, and deletes it in
  `@AfterEach` via the new `UserRepository.deleteById` — the same
  create-and-restore-afterward pattern `AccountPasswordControllerIntegrationTest` and
  `ForcedPasswordChangeLoginIntegrationTest` already use for the bootstrap account.
- `git diff` scope check: every changed/added file is under `engine/**` or
  `client/src/app/**`, matching the issue's Scope line exactly.

## Checks run

- `./mvnw -B test` — `BUILD SUCCESS`. Engine: 437 tests, 0 failures, 0 errors, 0
  skipped. Client (via the same Maven build, `npm run test:ci` / Karma + Chrome
  Headless): 493 of 493 executed, all passing (up from 468 before this task's new
  specs — `admin.service.spec.ts`, `admin-users.component.spec.ts`, and additions to
  `auth.service.spec.ts`/`app.component.spec.ts`).
- `./.t-workflow/scripts/consistency-check.sh` — `OK: all consistency checks passed`.
- `./.t-workflow/scripts/protected-paths.sh` against every touched main-source file —
  exit 1 (none protected), confirming the plan-gate exemption still holds.
- `git ... | .t-workflow/scripts/check-plan-gate.sh` — `OK: no protected path in this
  diff — no plan required`.
- `./.t-workflow/scripts/check-record.sh 240 <record>` — `OK: record matches task 240
  and carries every template section`.
- `./.t-workflow/scripts/check-manifest.sh` — reports `DRIFT: CONSTITUTION.md`. This
  predates this task entirely: `git diff HEAD -- CONSTITUTION.md` is empty (this task
  never touches the file), and the drift traces to already-merged `#237`
  (`cd557cc`, ratifying ADR-007 directly in `CONSTITUTION.md`) — the same
  known/pre-existing template gap #238's own record already documented ("a known,
  pre-existing template gap already hit by #237 and left to the human; neither file
  is in this task's Allowed paths"). Not fixed here for the same reason: `CONSTITUTION.md`
  is outside this task's Scope (`engine/**`, `client/src/app/**`).
