# 874 — Cut release v0.2.29
Issue: #874

## Asked
Cut release v0.2.29: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.30-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.29` has been run and `CHANGELOG.md` has a `## v0.2.29` section.
- `pom.xml`'s `<revision>` reads `0.2.30-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.29` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Ran `./scripts/generate-release-notes.sh generate --version 0.2.29` (11 commits v0.2.28..HEAD); bumped `<revision>` to `0.2.30-SNAPSHOT`.
- `scripts/check.sh` skips `./mvnw -B test` for this diff via `scripts/build-inputs.sh` (CHANGELOG + revision-only pom + record are not build inputs).

## Deviations / notes
- none
