# 1011 — Cut release v0.2.50
Issue: #1011

## Asked
Cut release v0.2.50: land its CHANGELOG.md section on main together with the
`<revision>` bump to `0.2.51-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.50` has been run
  and `CHANGELOG.md` has a `## v0.2.50` section.
- `pom.xml`'s `<revision>` reads `0.2.51-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate, with `0.2.50` as the workflow's
  input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- `<bump-version>` is the default patch increase, `0.2.51`, confirmed by the human
  when `/l-release` asked; no `<next-version>` override.
- The generated section has one entry (v0.2.49..HEAD holds one squash commit,
  #1010), under `### Other`; no editorial summary added.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-opus-5-5
