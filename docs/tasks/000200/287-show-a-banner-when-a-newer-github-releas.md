# 287 — Show a banner when a newer GitHub release is available

Issue: #287

## Asked
When a newer version of locklane has been published as a permanent GitHub release than
the one currently running, users should see a small, unobtrusive banner in the app
header telling them an update is available — informational only, with no in-app
mechanism to trigger the update itself. The engine periodically checks the repo's
latest permanent release (GitHub's "latest release" already excludes the rolling
`latest` pre-release the release pipeline publishes on every push) against the version
it was built with, and tells connected clients over the existing `/ws/events` channel
when it is newer.

## Done when
- The engine periodically queries the latest permanent GitHub release for this repo
  (via the `gh`-CLI-based pattern already used in
  `engine/src/main/java/dev/locklane/engine/github/`) and compares it against
  `BuildProperties.getVersion()`.
- When the latest release's version is newer than the running version, every connected
  client is told over `/ws/events`, and a newly-connecting client is told too.
- The Angular client shows a small banner (mounted the same way `<app-update-banner />`
  is, outside the login-gated block) whenever it has been told a newer release exists,
  and hides it otherwise. The banner states that a newer version is available; it does
  not offer or perform an update.
- No permanent `v*` release exists yet in this repo — the banner correctly stays hidden
  rather than erroring or showing on the rolling `latest` pre-release. Exercised by a
  test.
- `./mvnw -B test` and the client's test suite pass.

## Explicitly not
- Auto-updating, restarting, or otherwise acting on the new version.
- Comparing against the rolling `latest` pre-release, or against unreleased commits on
  `main`.
- Any settings/preferences UI for update checks (frequency, dismissal persistence, etc.)
  beyond the banner itself.

## Decisions made along the way
- `gh release view --repo <owner>/<repo> --json tagName` (no tag argument) is called
  with an explicit `--repo`, not relying on `gh`'s cwd-based auto-detection the way
  `CliGhClient` does for a managed project's workarea — the packaged jar has no
  guarantee of running inside a git checkout of this repo at all, so the repo is a
  config value (`locklane.release-check.repository`) instead (hani, 2026-08-28).
- Version comparison strips any `-SNAPSHOT`/qualifier suffix and compares the
  dot-separated numeric parts, so a `0.1.0-SNAPSHOT` build against a `v0.1.0` release
  reads as "not newer" (the SNAPSHOT is presumably the work leading up to that same
  release, not behind it) (hani, 2026-08-28).
- The "newer release" state is a one-way latch for the life of the process (it only
  ever moves from absent to present, or to a higher version) — the engine's own running
  version never changes at runtime and permanent releases are immutable, so there is no
  case where a previously-detected newer release should un-show itself (hani,
  2026-08-28).
- New component `app-release-banner`, not a reuse of `app-update-banner`: the existing
  one is specifically the client-bundle reload prompt from #273 (has a Reload button,
  driven by `SwUpdate`); this banner is purely informational and reuses only its
  mounting pattern in `app.component.html` (hani, 2026-08-28).

## Deviations / notes
- none
