# 997 — Cut release v0.2.47
Issue: #997

## Asked
Cut release v0.2.47: land its CHANGELOG.md section on main together with the
`<revision>` bump to `0.2.48-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.47` has been run
  and `CHANGELOG.md` has a `## v0.2.47` section.
- `pom.xml`'s `<revision>` reads `0.2.48-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate, with `0.2.47` as the workflow's
  input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Next snapshot `0.2.48-SNAPSHOT` is the default patch bump, confirmed by the human at `/l-release`'s start.
- CHANGELOG section written by `./scripts/generate-release-notes.sh generate --version 0.2.47`, unedited.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-opus-5-5
