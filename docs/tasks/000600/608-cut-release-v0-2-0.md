# 608 — Cut release v0.2.0
Issue: #608

## Asked
Cut release v0.2.0: land its CHANGELOG.md section on main together with the `<revision>`
bump to `0.2.1-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.0` has been run and
  `CHANGELOG.md` has a `## v0.2.0` section.
- `pom.xml`'s `<revision>` reads `0.2.1-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate, with `0.2.0` as the workflow's input; the
  bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- `<bump-version>` is the default patch increase, `0.2.1-SNAPSHOT` (the human confirmed
  the default at `/l-release`'s entry, 2026-09-02).

## Deviations / notes
- none
