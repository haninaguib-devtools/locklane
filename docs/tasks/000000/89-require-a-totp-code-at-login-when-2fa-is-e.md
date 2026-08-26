# 89 — Require a TOTP code at login when 2FA is enabled
Issue: #89 · Part of: #87

## Asked
When the admin account has 2FA enabled, the login flow challenges for a 6-digit TOTP
code after the password is verified, and only establishes a session once the code
checks out. Login is unchanged when 2FA is off.

## Done when
- `POST /api/auth/login` signals a pending-2FA challenge in its response when the
  account has 2FA enabled, without establishing a session.
- A new endpoint verifies the submitted code and, if correct, establishes the session.
- A wrong code is rejected and no session is created.
- Existing password-only login still works unchanged when 2FA is disabled.
- Tests cover both the 2FA-enabled and 2FA-disabled paths.

## Explicitly not
- The login page's UI for the code step — split to #92.

## Decisions made along the way
- none

## Deviations / notes
- none
