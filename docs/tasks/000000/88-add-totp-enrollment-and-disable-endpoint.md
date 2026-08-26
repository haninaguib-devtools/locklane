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
