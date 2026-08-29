# 238 — Add user roles and admin bootstrap
Issue: #238 · Part of: #236

## Asked
Give the app a real notion of "admin" versus "ordinary user" so later work (#239-#242)
can gate admin-only actions and per-owner access. Today `users` has no role column and
`EngineUserDetailsService` hands every logged-in user an empty authority list, so there
is no way to tell an admin from anyone else. Add a `role` column and a
"must change password" flag (needed by #240's forced-first-login flow) to the user
record, and make the account `UserBootstrapper` seeds on first run an admin.

## Done when
- A migration adds `role` (`ADMIN`/`USER`) and `must_change_password` (boolean, default
  false) columns to `users`, following the existing SQLite migration conventions.
- Any user row that pre-dates this migration (an existing single-user install) is
  backfilled to `role = ADMIN`.
- `UserBootstrapper`'s seeded account is created with `role = ADMIN`.
- `EngineUserDetailsService` grants a real Spring Security authority derived from the
  role (`ROLE_ADMIN` / `ROLE_USER`) instead of `List.of()`.
- `./mvnw -B test` passes.

## Explicitly not
- Admin UI/endpoints to create or delete other users — #239.
- The password-change endpoints and the forced-change-on-first-login gate itself — #240.
  This issue only adds the column and role plumbing they build on.
- Project ownership scoping — #239.
- Adding new `requestMatchers`/route gating in `SecurityConfig` for not-yet-existing
  admin endpoints — reserved to #239/#240 per the plan's Risks section.
- Filling in `CONSTITUTION.md` §3's reserved application-surfaces bullet and its
  matching `.t-workflow/scripts/protected-paths.sh` pattern for this surface — the
  plan's Risks section documents why (a known, pre-existing template gap already hit
  by #237 and left to the human; neither file is in this task's Allowed paths).

## Decisions made along the way
- Confirmed the next-free migration version against the branch's actual state
  (`ls engine/src/main/resources/db/migration/ engine/src/main/java/dev/locklane/engine/persistence/migration/`):
  SQL history runs V1/V2/V4/V7/V8, Java column-adding migrations sit at V3/V5/V6 — `V9`
  (the plan's guess) is still the first free number, so no renumbering was needed
  (t-work session, 2026-08-29).
- `UserRecord.Role` is a nested enum inside `UserRecord.java` rather than a new
  top-level file (`ProjectStatus.java` is the existing precedent for a top-level status
  enum, but the plan's Allowed paths lists `UserRecord.java` and no new enum file, so
  nesting keeps this task inside its own Allowed paths exactly).
- `UserRepository.create(username, passwordHash, now)` keeps its existing 3-argument
  signature — unchanged for its two out-of-scope callers
  (`engine/src/test/java/dev/locklane/engine/ws/AuthenticatedWebSocketClients.java`,
  a test helper not in this task's Allowed paths) — and now delegates to a new
  4-argument overload that takes `UserRecord.Role` explicitly, defaulting the 3-arg
  form to `Role.USER`. Both overloads always pass `role` explicitly in the `INSERT`
  rather than omitting it and relying on the migration's backfill `DEFAULT 'ADMIN'`,
  per the plan's Risks note that the default exists only to backfill pre-existing rows,
  not to decide the role of a row created after the migration has already run once.
  `UserBootstrapper` calls the new 4-arg overload with `Role.ADMIN`.
- `SecurityConfig` needed no change: granting the new authority happens entirely inside
  `EngineUserDetailsService`'s `UserDetails`, which Spring Security already threads
  into the `Authentication` it builds — no bean wiring in `SecurityConfig` is needed to
  make that authority exist, and no new `requestMatchers` were added (reserved to
  #239/#240 per Non-goals above).
- `must_change_password` defaults to `false` for every newly created account (including
  the bootstrap admin) by relying on the migration's column `DEFAULT 0` — unlike
  `role`, there is no "wrong value by accident" risk for a boolean whose default matches
  every current caller's intent, so no explicit parameter was added for it in this task;
  #240's forced-first-login flow is expected to add one when it starts setting it true
  for admin-created accounts.

## Deviations / notes
- None — implemented within the plan's Allowed paths as written, `V9` confirmed still
  free.
