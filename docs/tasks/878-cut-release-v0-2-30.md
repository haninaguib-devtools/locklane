# 878 — Cut release v0.2.30
Issue: #878

## Asked
Cut release v0.2.30: land its CHANGELOG.md section on main together with the
`<revision>` bump to `0.2.31-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.30` has been run
  and `CHANGELOG.md` has a `## v0.2.30` section.
- `pom.xml`'s `<revision>` reads `0.2.31-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges
  (`/l-release`, after this task's merge gate, with `0.2.30` as the workflow's
  input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- `./scripts/generate-release-notes.sh generate --version 0.2.30` wrote the `## v0.2.30` section (1 commit since v0.2.29: #876/#877); `<revision>` bumped to `0.2.31-SNAPSHOT` (confirmed default patch bump).

## Deviations / notes
- none

## Checks
- `scripts/check.sh` — SKIPPED (no build inputs in diff: CHANGELOG.md + revision-only pom.xml) — commit `HEAD`
- Note: an uncommitted `scripts/check.sh` run cannot decide (build-inputs compares `origin/main...HEAD`) and falls through to full `./mvnw -B test`, which fails on 4 pre-existing environmental engine tests (GH_TOKEN exported); irrelevant once committed.
