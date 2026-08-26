# 91 — Add the 2FA section to the settings dialog
Issue: #91 · Part of: #87

## Asked
Fill the settings dialog's body with a "Two-factor authentication" section that lets the
admin enable 2FA (show QR code + manual secret, verify a code) or disable it, wired to
the backend endpoints from #88.

## Done when
- The dialog shows the current 2FA status (enabled/disabled).
- Enable flow: fetch QR/secret, accept a 6-digit code, call confirm, update status on
  success, show an inline error on an invalid code.
- Disable flow: confirm and call disable, update status.
- The three states (off / enrolling / enabled) match the approved mockup.

## Explicitly not
- Backup/recovery codes.

## Decisions made along the way
- No mockup was findable anywhere (issue, comments, #87, docs/) — asked the human, who
  said to design the three states directly rather than wait, matching `portstow`'s
  settings-page visual pattern (styled with locklane's own colors, per #87's goal) (hani,
  2026-08-26).

## Deviations / notes
- Touched `client/src/app/app.component.spec.ts`, outside the declared scope
  (settings-dialog component + `client/src/app/services/**`): the settings dialog now
  fires `GET /api/account/2fa/status` on open, which an existing app.component test
  opened the dialog but never flushed, so it failed `httpMock.verify()`. Added the flush;
  no other change to that file.
