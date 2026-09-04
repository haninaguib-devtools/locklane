# 669 — Cut release v0.2.9
Issue: #669

## Asked
Cut release v0.2.9: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.10-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.9` has been run and `CHANGELOG.md` has a `## v0.2.9` section.
- `pom.xml`'s `<revision>` reads `0.2.10-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.9` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Generated the `v0.2.9` changelog section from the commits since `v0.2.8` with `scripts/generate-release-notes.sh` (haninaguib via `/l-release`, 2026-09-03).
- Bump target `0.2.10-SNAPSHOT` is the `/l-release` default patch increase, confirmed by the human at the skill's entry point (2026-09-03).

## Deviations / notes
- none
