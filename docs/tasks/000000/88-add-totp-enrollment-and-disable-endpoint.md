# 88 — Add TOTP enrollment and disable endpoints to the account API
Issue: #88 · Part of: #87

## Asked
Add backend support for enabling and disabling TOTP-based 2FA on the admin account:
generate a secret, return a QR code and manual key, verify a 6-digit code to confirm
enrollment, and let the admin disable 2FA once verified.

## Done when
- New column(s) on the `users` table for the TOTP secret and an enabled flag (added to
  `engine/src/main/resources/schema.sql`).
- `POST /api/account/2fa/enroll` starts enrollment: generates a secret, returns it as a
  QR code data URI plus the manual key.
- `POST /api/account/2fa/confirm` verifies a 6-digit code against the pending secret and
  marks 2FA enabled.
- `POST /api/account/2fa/disable` clears the secret and disables 2FA (requires the current
  password).
- `GET /api/account/2fa/status` returns whether 2FA is currently enabled.
- Unit tests cover enroll, confirm (valid and invalid code), and disable.

## Explicitly not
- Enforcing 2FA at login — #89.
- Backup/recovery codes — #93.

## Decisions made along the way
- **TOTP is implemented in-repo (`TotpService`), not pulled from a library.** RFC 6238 over
  RFC 4226 is HMAC-SHA1 plus dynamic truncation — roughly forty lines against the JDK's own
  `javax.crypto.Mac`, with no third-party code in the authentication path. Verified against
  the published RFC 6238 test vectors rather than against itself (haninaguib, 2026-08-26).
- **Base32 (RFC 4648, unpadded) is also hand-rolled**, in `TotpService`. It exists only
  because authenticator apps read secrets in that alphabet; the JDK ships Base64 but not
  Base32, and one small encoder/decoder is cheaper than a dependency for it
  (haninaguib, 2026-08-26).
- **The QR image uses `com.google.zxing:core` 3.5.4** — a new dependency in `engine/pom.xml`
  (outside the issue's Scope line; approved in the moment, see Deviations). `core` alone
  produces the bit matrix; the PNG is written with the JDK's own `ImageIO`, so the
  `zxing:javase` companion artifact is not needed (haninaguib, 2026-08-26).
- **One column pair carries both the pending and the confirmed secret**: `totp_secret` plus
  `totp_enabled`. A secret with `totp_enabled = 0` is an enrollment the admin started and
  has not yet proved they can generate codes for; confirm flips the flag, disable clears
  both. A separate "pending" column would have to be reconciled with the live one on every
  read for no behavioural gain, and this shape survives a restart mid-enrollment
  (haninaguib, 2026-08-26).
- **The secret is encrypted at rest with the existing `TokenCipher`** (#81), same as a
  project's GitHub token — the `totp_secret` column never holds a Base32 secret in the
  clear (haninaguib, 2026-08-26).
- **Enrolling while 2FA is already enabled is a 409, not a silent re-issue.** Re-enrollment
  has to go through disable, which costs the current password; without that, a session
  someone else got hold of could quietly move 2FA to their own authenticator without ever
  knowing the password (haninaguib, 2026-08-26).
- **Verification accepts the immediately preceding and following 30-second step** (±1), the
  conventional allowance for clock skew between the phone and the server. A wider window
  buys little and lengthens how long a captured code stays usable (haninaguib, 2026-08-26).
- `totp_secret` / `totp_enabled` are added straight into the existing
  `CREATE TABLE IF NOT EXISTS users (...)`, matching the precedent #48/#52/#81 set for this
  codebase (there is still no migration tooling) — a database created before this task will
  not gain the columns, and the account endpoints will fail against it until the local
  `locklane.db` is dropped and recreated.

## Deviations / notes
- **Two paths outside the issue's Scope line were needed and approved in the moment**
  (haninaguib, 2026-08-26):
  - `engine/pom.xml` — the zxing dependency, without which the endpoint cannot return the
    QR code data URI the done-when asks for. The alternative offered was returning only the
    `otpauth://` URI and pushing QR rendering onto the client task (#91); the human chose
    the dependency.
  - `engine/src/main/java/dev/locklane/engine/persistence/` (`UserRecord`, `UserRepository`)
    — every other query against the `users` table already lives there, and the alternative
    offered was a second repository class inside `security/` issuing its own SQL against the
    same table. The human chose to keep one table's SQL in one place.
- The full suite failed on the first run against a **leftover test database** in
  `${java.io.tmpdir}/locklane-engine-test` from earlier sessions — `no such column:
  totp_secret`, the same precedented gap #81 hit and recorded. Deleting that directory and
  re-running was the fix; CI starts clean and never sees it. Worth an issue of its own (see
  the report), because every subsequent schema change will reproduce it (haninaguib,
  2026-08-26).
- Checks after that: `./mvnw -B test` green — 171 tests, 0 failures, 0 errors, including 9
  new in `TotpServiceTest`, 11 in `AccountTwoFactorIntegrationTest`, and 4 added to
  `UserRepositoryTest`. `./scripts/consistency-check.sh` passed (haninaguib, 2026-08-26).

## Fix pass — review findings on PR #94 (2026-08-26)

- **High, scope drift.** The review found that `engine/pom.xml` and the two files under
  `engine/src/main/java/dev/locklane/engine/persistence/` sit outside the issue's Scope
  line, and that `engine/src/test/java/**` was never named there either even though the
  issue's Done-when requires tests. The two deviations above were disclosed and approved
  in the moment, but the issue itself still said otherwise, so a cold session reading the
  issue could not tell what this task was allowed to touch. Answered by widening the
  issue's Scope line to the paths the work actually needed — the two persistence files,
  `engine/pom.xml`, and `engine/src/test/java/**` — so the issue and this record agree.
  No code was undone (haninaguib asked for this in the moment, 2026-08-26).
- **Medium, confirm reported success without checking the write.** `enableTotp` scopes its
  UPDATE to a row that still has a secret, which is the right guard, but the controller
  discarded the result and always answered `enabled: true`. A disable from another session
  landing between the read and the update made the UPDATE a no-op while the user was told
  two-factor authentication was now on. `UserRepository.enableTotp` now returns the number
  of rows changed and `AccountTwoFactorController.confirm` answers 409 on zero, telling the
  user the enrollment was cleared and to start again. `UserRepositoryTest` asserts both
  counts — 1 where an enrollment exists, 0 where it does not. The controller's zero-row
  branch is not exercised end to end: forcing it through MockMvc would mean interleaving two
  requests inside one call, so the count that drives it is pinned at the repository instead
  (haninaguib, 2026-08-26).
- The three low findings — no throttling on `disable`'s password check, `URLEncoder`
  rendering a space in a username as `+`, and `verify` throwing rather than returning false
  on an empty secret — were left alone. Fix mode addresses blocker and high findings only,
  and the human did not ask for these by number.
- Checks after the fix: `./mvnw -B test` green — 171 tests, 0 failures, 0 errors.
  `./scripts/consistency-check.sh` passed (haninaguib, 2026-08-26).
