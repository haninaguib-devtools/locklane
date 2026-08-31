# 465 — Install and update from permanent releases; retire the rolling latest
Issue: #465 · Part of: #462

## Asked
Make what the update installs and what the in-app banner announces the same artifact.
Today `install.sh` and `update.sh` download `locklane.jar` from the rolling `latest`
pre-release, while the banner (`ReleaseUpdateChecker` via `CliReleaseClient`) announces
the newest *permanent* non-prerelease `v*` release — two different channels, so updating
can install something other than what was announced and the banner may not clear. Switch
both scripts to download the jar of the newest permanent release, and retire the rolling
`latest` pre-release from `.github/workflows/release.yml` now that nothing consumes it.

## Done when
- A fresh `install.sh` run and an `update.sh` run both fetch the jar attached to the
  newest non-prerelease `v*` release.
- `release.yml` no longer creates or updates a rolling `latest` pre-release.
- After updating, the running version equals the release the banner announced, and the
  banner no longer shows (verifiable manually: banner names `vX.Y.Z`, update, banner
  gone).
- The documented install one-liner in `README.md` still works unchanged.

## Explicitly not
- No change to the install/update user interface (same one-liner, same scripts, same
  service handling) — only the download source changes.
- No in-app self-update; updating remains running `update.sh`.
- No deletion of the already-published `latest` pre-release or its tag on the forge —
  that is a forge action proposed to the human in the Plan's human checks, not repo
  content.

## Decisions made along the way
- The scripts call `gh release download` with **no tag argument**: gh then resolves
  GitHub's "latest release" — the newest non-prerelease, non-draft release — which is
  exactly what the banner's `CliReleaseClient` resolves with a tagless
  `gh release view`, so scripts and banner share one rule by construction (agent,
  2026-08-31, pinned in the issue's Plan).
- `release.yml`'s concurrency group renamed `release-latest` → `release`; the
  cancel-in-progress rationale is reworded onto the immutability check (two concurrent
  publishes of the same version must not race past the pre-create existence check)
  (agent, 2026-08-31).
- The stage step's `title` output (version + build number + short sha) is removed with
  the upsert step — the rolling release's title was its only consumer; the permanent
  release keeps titling itself `v<version>` (agent, 2026-08-31).
- `docs/architecture/releasing.md` keeps one historical mention that the rolling
  `latest` existed and was retired by #465 — stating the channel is gone is not
  claiming it is still published, and it explains what old installs still pin
  (agent, 2026-08-31).

## Deviations / notes
- Driven-run base: this task is a child of initiative #462 driven by `/t-drive 462`
  (ADR-004). The branch was created by the driving session from `wip/462-integration`,
  not `main`, and the draft PR targets `wip/462-integration`; the PR body carries no
  auto-close phrase — the initiative's single aggregate PR to `main` closes #465 later.
  Phase 1's rebase-onto-`origin/main` step was skipped on the driving session's explicit
  direction (integration base is the design).
