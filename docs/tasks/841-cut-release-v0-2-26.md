# 841 — Cut release v0.2.26
Issue: #841

## Asked
Cut release v0.2.26: land its CHANGELOG.md section on main together with the
`<revision>` bump to `0.2.27-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.26` has been run
  and `CHANGELOG.md` has a `## v0.2.26` section.
- `pom.xml`'s `<revision>` reads `0.2.27-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate, with `0.2.26` as the workflow's
  input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Version 0.2.26 and the default patch bump to 0.2.27-SNAPSHOT were both confirmed by
  the human at `/l-release`'s entry point; `scripts/release.sh gate 0.2.26` passed
  before this task was opened.
- The CHANGELOG section was generated, not hand-written: two squash commits since
  v0.2.25 (#837 under Fixes, #840 under Other), dated 2026-09-08.

## Deviations / notes
- The diff is not documentation-only (`pom.xml` changes), so check 1 runs locally
  even though the PR's CI skips Maven for a `<revision>`-only `pom.xml` change
  (`scripts/build-inputs.sh`, ADR-109 D3).
