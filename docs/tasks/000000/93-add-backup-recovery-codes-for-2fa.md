# 93 — Add backup/recovery codes for 2FA
Issue: #93 · Split from: #87

## Asked
Let the admin generate one-time backup codes when enabling 2FA, so they aren't locked
out of the account if they lose their authenticator device.

## Done when
- Enabling 2FA generates a set of one-time backup codes shown once.
- A backup code can be used in place of a TOTP code at login, and is consumed after use.
- The admin can regenerate the backup code set from the settings dialog.

## Explicitly not
- Rate-limiting or lockout on repeated failed login attempts (neither TOTP nor backup
  codes have this today — out of scope here).
- Showing a remaining-codes count in the settings dialog; the done-when only asks for
  generate/use/regenerate.

## Decisions made along the way
- Backup codes are 10 codes of the form `XXXXX-XXXXX` (10 hex digits), generated
  together and stored as BCrypt hashes in a new `backup_codes` table — the same
  hashing the account password already uses, rather than reversible encryption like
  the TOTP secret, since a backup code only ever needs to be *matched*, never read
  back out.
- Regenerating the set costs the current password, the same as disabling 2FA: a
  stolen session cookie must not be able to mint itself a fresh set of standing
  recovery credentials, matching the threat model `AccountTwoFactorController`
  already documents for `disable`.
- The codes are generated and shown exactly once, at the moment `confirm` turns 2FA
  on, and again whenever regenerated — never re-displayed afterward.
- Login's code field now accepts either shape (6-digit TOTP or `XXXXX-XXXXX` backup
  code): the input's `inputmode`/`maxlength` were relaxed and the hint text updated;
  the backend tries a TOTP match first, then a backup code.

## Deviations / notes
- None.
