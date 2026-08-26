# 92 — Add the TOTP code step to the login page
Issue: #92 · Part of: #87

## Asked
When login signals a pending-2FA challenge (from #89), the login page shows a second
step asking for the 6-digit code, and only proceeds once it's verified.

## Done when
- The login page detects the pending-2FA response and shows a code-entry step.
- Submitting a valid code completes login and proceeds as today.
- An invalid code shows an inline error and stays on the code step.
- Login is unchanged for accounts without 2FA.

## Explicitly not
- No changes to the engine's login or verify endpoints — #89 shipped those; this task
  consumes them as-is.
- No "remember this device" or recovery-code affordances — nothing in #87 orders them.

## Decisions made along the way
- `AuthService.login` now returns `Observable<LoginResult>` (`{ twoFactorRequired }`)
  instead of `Observable<void>`, and only flips `isLoggedIn` when no code is pending —
  the engine answers 200 in both cases, so the body is the only signal (Claude,
  2026-08-26). The login component is the method's only caller, so no other call site
  changes.
- The code step reuses the same card and error styling as the credentials step; the
  code input uses `autocomplete="one-time-code"` and `inputmode="numeric"` so mobile
  keyboards and OTP autofill behave (Claude, 2026-08-26).

## Deviations / notes
- none
