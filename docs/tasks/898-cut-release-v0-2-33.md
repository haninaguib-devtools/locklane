# 898 — Cut release v0.2.33
Issue: #898

## Asked
Cut release v0.2.33: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.34-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.33` has been run and `CHANGELOG.md` has a `## v0.2.33` section.
- `pom.xml`'s `<revision>` reads `0.2.34-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.33` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Ran `./scripts/generate-release-notes.sh generate --version 0.2.33` (wrote v0.2.33 section, v0.2.32..HEAD, 1 commit); bumped `pom.xml` `<revision>` 0.2.33-SNAPSHOT → 0.2.34-SNAPSHOT.

## Deviations / notes
- none

## Agents
- work: opencode / meta/muse-spark-1.3-contributor
