# 1023 — Cut release v0.2.52
Issue: #1023

## Asked
Cut release v0.2.52: land its CHANGELOG.md section on main together with the
`<revision>` bump to `0.2.53-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.52` has been run
  and `CHANGELOG.md` has a `## v0.2.52` section.
- `pom.xml`'s `<revision>` reads `0.2.53-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate, with `0.2.52` as the workflow's
  input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- none

## Deviations / notes
- none

## Agents
- work: claude-code / claude-opus-5-5
