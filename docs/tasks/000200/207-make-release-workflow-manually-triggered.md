# 207 — Make release workflow manually triggered
Issue: #207

## Asked
`.github/workflows/release.yml` currently triggers on every push to `main`, rebuilding
and retesting the whole project (`./mvnw -B package`) to publish a rolling "latest"
release and, when the version has dropped `-SNAPSHOT`, a permanent `v<version>` release.
This duplicates the build/test work `ci.yml` already did on that same commit and burns
GitHub Actions minutes on every merge to main. Switch its trigger to manual only
(`workflow_dispatch`), so a release is published on demand instead of automatically on
every merge.

## Done when
- `.github/workflows/release.yml`'s `on:` block no longer includes `push: branches:
  [main]`, and includes `workflow_dispatch: {}` instead.
- A push to `main` no longer starts a run of this workflow.
- The workflow still runs successfully when triggered manually (`gh workflow run
  release.yml` or the Actions UI), still publishing the rolling "latest" release and,
  when applicable, the permanent `v<version>` release.

## Explicitly not
- No change to `ci.yml`'s triggers or concurrency handling.
- No change to `stale-branches.yml`'s hourly cron cadence.
- No change to what the release job actually does (build steps, tagging/release logic) —
  trigger mechanism only.

## Decisions made along the way
- none

## Deviations / notes
- none
