# 1019 — Cut release v0.2.51
Issue: #1019

## Asked
Cut release v0.2.51: land its CHANGELOG.md section on main together with the
`<revision>` bump to `0.2.52-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.51` has been run
  and `CHANGELOG.md` has a `## v0.2.51` section.
- `pom.xml`'s `<revision>` reads `0.2.52-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate, with `0.2.51` as the workflow's
  input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- `<bump-version>` is the default patch increase, `0.2.52`, confirmed by the human
  when `/l-release` asked; no `<next-version>` override.
- The generated section has one entry (v0.2.50..HEAD holds one squash commit,
  #1018 for initiative #1013), under `### Other`; no editorial summary added.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-opus-5-5
