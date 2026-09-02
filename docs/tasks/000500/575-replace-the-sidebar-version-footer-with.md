# 575 — Replace the sidebar version footer with an About dialog
Issue: #575

## Asked
The engine's running version was shown as a one-line footer under the usage widget
at the bottom of the sidebar (`.app-version`, #467). Remove that footer, and instead
add an "About" item to the avatar (account) menu in the top bar that opens a small
dialog showing the app name and the running engine version. The sidebar bottom then
holds only the usage widget.

## Done when
- The sidenav no longer renders `.app-version`; its footer specs are replaced by
  specs for the About dialog.
- The account menu has an "About" item after "GitHub accounts" / "Manage users" and
  before "Sign out".
- Clicking it opens `app-about-dialog` (same overlay pattern as `confirm-dialog`)
  showing "LockLane" and the version from `RunningVersionService`, or "version
  unknown" until one is known. Escape and a Close button dismiss it.
- `grep -rn "app-version" client/src` returns nothing; `./mvnw -B test` and
  `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- No change to how the version is obtained (`RunningVersionService` stays as is).
- No console sizing changes — split to #574.

## Decisions made along the way
- The dialog is its own standalone component under `components/about-dialog/`,
  copying `confirm-dialog`'s backdrop/panel styling rather than reusing it, since it
  has no confirm/cancel pair (agent, 2026-09-02).
- The "About" item sits directly before "Sign out", after "Manage users" for an admin,
  so the conventional last-item position of Sign out is kept (agent, 2026-09-02).

## Deviations / notes
- `RunningVersionService`'s doc comment still says the version is "for the sidenav
  footer to display"; that file is outside this task's scope, so the stale wording is
  left for a later ride-along.
