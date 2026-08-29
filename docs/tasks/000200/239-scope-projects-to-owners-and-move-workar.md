# 239 — Scope projects to owners and move workareas under per-user directories
Issue: #239 · Part of: #236

## Asked
Projects are global today: the `projects` table has no owner column, and any
authenticated user can list, read, or act on any project. Make ownership real: every
project belongs to the user who created it (or is visible to an admin), enforced in the
application/query layer — not by where its checkout happens to sit on disk. Alongside
that, move workarea checkouts from `workareas/<project-slug>` to
`workareas/<user_id>/<project-slug>`, purely to keep the on-disk layout organized per
owner (this is not itself the security boundary).

## Done when
- A migration adds `owner_user_id` (FK to `users.id`) to `projects`; existing rows are
  backfilled to the bootstrapped admin's user id (per #238's backfill).
- Every project read/write path (`ProjectRepository`/`ProjectController` and anything
  built on them) filters or authorizes by "owned by the authenticated user, or the
  caller is admin" — a non-owner, non-admin request for someone else's project is
  rejected rather than served.
- `ProjectCheckoutService` (or wherever workarea paths are resolved today) resolves new
  checkouts under `workareas/<owner_user_id>/<slug>` instead of `workareas/<slug>`.
- Existing on-disk workarea directories for already-created projects are relocated to
  match the new layout (a one-time migration step, run once per existing project as part
  of this change).
- `./mvnw -B test` passes.

## Explicitly not
- Worktree/console session ownership semantics — a later issue in this initiative,
  which depends on this one.
- Admin user-management UI — a later issue in this initiative.
- No `## Plan` section exists on the issue: its declared Scope
  (`engine/**`: `ProjectRecord`, `ProjectRepository`, `ProjectController`,
  `ProjectCheckoutService`, a new Flyway migration) was confirmed to touch no path
  `.t-workflow/scripts/protected-paths.sh` currently protects
  (`./.t-workflow/scripts/protected-paths.sh <the five touched main-source files>` exits
  1 — none protected), so no plan-gate applies; the issue's own Scope line is the
  binding boundary per AGENTS.md.

## Decisions made along the way
- **Migration is `V10`** (Java, conditional `ADD COLUMN`, following `V9`'s pattern) —
  confirmed the next-free version by listing both migration directories
  (`engine/src/main/resources/db/migration/` has V1/V2/V4/V7/V8; the Java
  column-adding migrations under `dev.locklane.engine.persistence.migration` sit at
  V3/V5/V6/V9) — `V10` is the first free number (t-work session, 2026-08-29).
- **`owner_user_id` is left nullable at the SQLite column level.** SQLite's
  `ALTER TABLE ... ADD COLUMN` cannot add a `NOT NULL` column without a static default,
  and the backfill target (the admin's row id) isn't known until the migration runs and
  queries `users`. `ProjectRepository.create`/`createReady` both require the parameter
  explicitly (no default), so no row created by the application after this migration is
  ever left without an owner — same reasoning V9 already established for `role`.
- **Admin lookup for backfill reuses #238's own convention**: the lowest-id row in
  `users` with `role = 'ADMIN'` (`SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id
  LIMIT 1`), mirroring how `UserBootstrapper`/V9 treat "the bootstrapped admin" as the
  first-ever account. No admin found (a brand-new install where `UserBootstrapper`
  hasn't run yet) is a no-op — there are no pre-existing project rows to backfill either,
  since project creation has always required an authenticated caller.
- **The workarea relocation runs inside the same Flyway migration**, not a separate
  `ApplicationRunner` step, because it needs no Spring wiring: the new path is derived
  purely from each row's own existing (absolute) `workarea_path` by inserting the
  owner-id directory directly above the leaf slug directory
  (`<parent>/<slug>` → `<parent>/<owner_user_id>/<slug>`) — the configured
  `workarea-root` property never needs to be read. `Files.move` on a plain directory is
  a single atomic rename (not a recursive copy) when source and destination share a
  filesystem, which they always do here (both under the same configured root), so a
  crash mid-move never leaves a half-moved directory.
- **Relocation is crash-safe and idempotent by construction**, not by a manual "already
  ran" flag: only rows with `owner_user_id IS NULL` are considered; the move itself is
  skipped (not an error) when the source directory doesn't exist (nothing was ever
  checked out — still `CLONING`, or `FAILED` with the directory already cleaned up by
  `ProjectCheckoutService`) or the destination already exists (an earlier, interrupted
  attempt already moved it before crashing prior to recording the DB update); either way
  the DB row is still updated so a retry never gets stuck. Verified directly in
  `SchemaMigrationTest` (moves a real directory with a marker file, and separately
  covers a project with no on-disk checkout at all).
- **`ProjectController` returns 404, not 403, for "exists but isn't yours."** A
  non-owner, non-admin request for someone else's project id gets exactly the same
  response as a request for an id that doesn't exist at all — deliberately
  indistinguishable, so a caller can't use the response to enumerate which project ids
  belong to other accounts. `retry`/`delete`/`github-token` all funnel through one
  `findAuthorized(id, authentication)` helper that returns empty in both cases; `list`
  filters at the repository query layer instead (`findAllOwnedBy` for an ordinary
  caller, `findAll` for admin), per ADR-007's "filters or checks against it in Java, at
  the ProjectController/ProjectRepository layer."
- **Caller identity comes from `Authentication` + `UserRepository.findByUsername`**,
  the same pattern already used by `AccountTwoFactorController`/`AuthController`
  (`authentication.getName()` is the username; `EngineUserDetailsService`'s principal
  carries no user id or role, only the username and derived `ROLE_*` authority) — no
  new lookup mechanism was needed. `SecurityConfig` already gates every project
  endpoint as `authenticated()`, so `authentication` is never null.
- **`ProjectRecord`/`ProjectRepository.create`/`createReady`/`ProjectCheckoutService
  .createProject` all gained a required `ownerUserId`/`long` parameter** rather than an
  optional or defaulted one, to make "every project has an owner from the moment it's
  created" a compile-time property, not a runtime convention. This forced a mechanical
  (argument-only) update to every other test file that calls `ProjectRepository
  .create`/`createReady` for setup unrelated to ownership itself (worktree/console/issue
  tests) — none of those files test ownership behavior, so each was given a fixed
  placeholder owner id (`1L`); SQLite's `foreign_keys` pragma is off throughout this
  codebase (confirmed: no `PRAGMA foreign_keys` anywhere), so an owner id with no
  matching `users` row in those tests doesn't error.
- **`ProjectView` (the JSON shape `ProjectController` returns) gained `ownerUserId`.**
  Small, direct reflection of the new column, entirely within `ProjectController`'s own
  scope (no client change) — worth having so a caller (in particular a future
  admin-facing view) can tell whose project is whose, even though rendering that is
  explicitly a later issue's job.
- **`ProjectCheckoutService.clone()`'s directory creation changed from
  `Files.createDirectories(workareaRoot)` to `Files.createDirectories(project
  .workareaPath().getParent())`** — needed once every workarea path gained an
  owner-id segment between the configured root and the slug, so the per-owner
  subdirectory (not just the top-level root) reliably exists before `git clone` writes
  into it.

## Deviations / notes
- `check-blocker-gate.sh` reports both `#237` and `#238` as still `OPEN` on the
  tracker — expected for a driven-initiative run (ADR-004): both issues' merge commits
  (`cd557cc`, `4ada5d6`) are confirmed as ancestors of this branch's `HEAD`
  (`git merge-base --is-ancestor`), so the actual dependency is satisfied even though
  the tracker issues stay open until the whole initiative ships to `main`. Identical
  situation already noted in #238's own task record.
- No plan section exists on #239 by design (confirmed via `protected-paths.sh` against
  every touched main-source file, per the issue's Scope line) — see Explicitly-not
  above.
