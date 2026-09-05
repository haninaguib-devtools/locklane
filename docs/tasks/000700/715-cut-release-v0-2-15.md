# 715 — Cut release v0.2.15
Issue: #715

## Asked
Cut release v0.2.15: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.16-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.15` has been run and `CHANGELOG.md` has a `## v0.2.15` section.
- `pom.xml`'s `<revision>` reads `0.2.16-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.15` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- none

## Deviations / notes
- none
