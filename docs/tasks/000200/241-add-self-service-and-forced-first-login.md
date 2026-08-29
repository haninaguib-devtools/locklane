# 241 — Add self-service and forced-first-login password change
Issue: #241 · Part of: #236

## Asked
Any user needs a way to change their own password, and an admin-created account (#240
sets `must_change_password = true` on creation) must be forced through that same change
right after its first successful login, before it can use the rest of the app —
mirroring the existing post-login 2FA gate (`PendingTwoFactorLogin` /
`TwoFactorAwareLoginSuccessHandler`), which already stages a second step after password
auth succeeds.

## Done when
- An authenticated endpoint accepts current password + new password and updates the
  stored hash; on success it clears `must_change_password` if it was set.
- The login flow checks `must_change_password` the same way it already checks pending
  2FA, and blocks access to the rest of the app until the user has set a new password.
- The Settings dialog (`client/src/app/components/settings-dialog/`) gets a new panel,
  alongside the existing 2FA panel, for self-service password change.
- A forced-change screen is shown client-side when the login response indicates
  `must_change_password` is set, before the normal app renders.
- `./mvnw -B test` passes; client tests pass.

## Explicitly not
- Admin-side user creation/deletion, and the only in-scope way to actually flip
  `must_change_password` to `true` on a real account — #240.
- Project ownership scoping — #239 (concurrent sibling task on the same integration
  branch; no `Project*` file was touched).

## Decisions made along the way
- Mirrored the 2FA staged-login pattern exactly rather than the alternative of
  authenticating the session immediately and gating every other endpoint behind a new
  filter: `PendingPasswordChangeLogin` is a second session-attribute key alongside
  `PendingTwoFactorLogin`, and `TwoFactorAwareLoginSuccessHandler` stages one or the
  other (2FA checked first) instead of establishing a session, exactly as it already did
  for 2FA. `AuthController.changePendingPassword` (`POST /api/auth/password/change`,
  outside `authenticated()`) is the mirror image of `verifyTwoFactor`: it reads the
  pending session, checks the temporary current password, replaces it, clears
  `must_change_password`, and only then establishes the real session. This means "blocks
  access to the rest of the app" is enforced the same way 2FA already enforces it — no
  session exists yet — rather than needing a new authenticated-but-restricted filter
  (session, 2026-08-29).
- Client-side, extended `LoginComponent`'s existing two-step
  (`credentials`/`code`) machine to a third step (`password-change`) instead of gating
  at `AppComponent` level, since the pending state is unauthenticated (`isLoggedIn`
  stays false) exactly like the 2FA step — no `AppComponent`/routing change was needed.
  The step reuses `password` (the temporary password just typed) as the endpoint's
  `currentPassword`, so the user is never asked to re-type it (session, 2026-08-29).
- Self-service password change is a *separate* endpoint, `POST /api/account/password`
  (new `AccountPasswordController`, gated `authenticated()` in `SecurityConfig`
  alongside `/api/account/2fa/**`), rather than reusing the pending-login endpoint —
  matching the existing shape where 2FA has both an authenticated self-service surface
  (`/api/account/2fa/**`) and a separate unauthenticated login-completion endpoint
  (`/api/auth/2fa/verify`). It always clears `must_change_password` too, so a plain
  voluntary change also satisfies the flag if it happened to be set (session,
  2026-08-29).
- `UserRepository.changePassword(username, newPasswordHash)` is the one place both
  endpoints update the hash — a single `UPDATE ... SET password_hash = ?,
  must_change_password = 0` — rather than each endpoint updating the hash and clearing
  the flag as two separate statements (session, 2026-08-29).
- Left the co-occurring edge case (`totpEnabled` and `mustChangePassword` both true on
  the same account) without its own gate: after `verifyTwoFactor` establishes a session,
  nothing currently blocks other endpoints until the self-service `/api/account/password`
  call clears the flag. Documented in `TwoFactorAwareLoginSuccessHandler`'s javadoc and
  `SecurityConfig` rather than built around, because no in-scope flow can produce that
  combination — `must_change_password` is only ever set by #240's (not-yet-built)
  admin-create flow, and an admin-created account has never had the chance to enable 2FA
  yet. Flagged here rather than silently assumed away (session, 2026-08-29).

## Deviations / notes
- `must_change_password` has no in-scope way to become `true` on a real account yet
  (#240 owns that). Both new integration tests
  (`ForcedPasswordChangeLoginIntegrationTest`, and the "wrong password" case in
  `AccountPasswordControllerIntegrationTest`) flip the column directly via a raw
  `JdbcTemplate` UPDATE against the bootstrap test user (restored in `@AfterEach`, the
  same pattern `TwoFactorLoginIntegrationTest` already uses for 2FA state) — no
  production code needed for this, since `UserRepository` already exposed the column via
  `findByUsername`.
