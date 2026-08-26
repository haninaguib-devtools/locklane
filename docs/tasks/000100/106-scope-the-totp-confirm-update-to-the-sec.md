# 106 — Scope the TOTP confirm update to the secret that was verified
Issue: #106 · Part of: #87

## Asked
Confirming two-factor enrollment can switch 2FA on against a secret the confirming
request never checked. `AccountTwoFactorController.confirm` reads the pending secret,
verifies the submitted code against it, then calls `UserRepository.enableTotp`, whose
UPDATE is scoped only to `totp_secret IS NOT NULL` — not to the specific secret that was
verified. Meanwhile `POST /api/account/2fa/enroll` freely replaces a pending secret. So a
second enrollment landing between the read and the update can leave 2FA enabled against
a newer secret than the one the user's code actually proved. Fix: scope the UPDATE to the
exact encrypted secret that was verified, so a replaced secret matches no row.

## Done when
- `UserRepository.enableTotp` takes the encrypted secret that was verified and scopes its
  UPDATE to it (`totp_secret = ?`).
- `AccountTwoFactorController.confirm` passes the ciphertext it read; the existing
  zero-rows branch (409, "that enrollment was cleared before it could be confirmed; start
  again") covers the replaced case too.
- A test proves the hole is closed: enroll, capture the first secret, enroll again, then
  confirm with a valid code generated from the first secret — 409, `totp_enabled` stays 0.
- `UserRepositoryTest` covers `enableTotp` returning 0 when the stored secret differs from
  the one passed.
- `./mvnw -B test` passes.

## Explicitly not
- Rate limiting the password check on `/2fa/disable` (and `/api/auth/login`) — separate
  weakness, not this one.
- Rejecting a second `enroll` while one is pending — would also close this hole but breaks
  the deliberate retry behaviour. Scoping the UPDATE keeps both.
- Enforcing 2FA at login — #89.

## Decisions made along the way
- none

## Deviations / notes
- none
