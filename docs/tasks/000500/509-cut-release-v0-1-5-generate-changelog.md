# 509 — Cut release v0.1.5: generate CHANGELOG.md section

Issue: #509

## Asked
Cut release v0.1.5: generate its CHANGELOG.md section and land it on main.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.1.5` has been run
  and `CHANGELOG.md` has a `## v0.1.5` section.
- The section is reviewed and merged to `main`.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate).

## Decisions made along the way
- none

## Deviations / notes
- none
