# 486 — Cut release v0.1.1: generate CHANGELOG.md section
Issue: #486

## Asked
Cut release v0.1.1: generate its CHANGELOG.md section and land it on main.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.1.1` has been run and `CHANGELOG.md` has a `## v0.1.1` section.
- The section is reviewed and merged to `main`.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate).

## Decisions made along the way
- none

## Deviations / notes
- none
