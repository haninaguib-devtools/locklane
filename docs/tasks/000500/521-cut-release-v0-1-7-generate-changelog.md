# 521 — Cut release v0.1.7: generate CHANGELOG.md section
Issue: #521

## Asked
Cut release v0.1.7: generate its CHANGELOG.md section and land it on main.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.1.7` has been run and `CHANGELOG.md` has a `## v0.1.7` section.
- The section is reviewed and merged to `main`.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate).

## Decisions made along the way
- none

## Deviations / notes
- none
