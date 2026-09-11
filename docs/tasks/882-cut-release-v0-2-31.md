# 882 — Cut release v0.2.31
Issue: #882

## Asked
Cut release v0.2.31: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.32-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.31` has been run and `CHANGELOG.md` has a `## v0.2.31` section.
- `pom.xml`'s `<revision>` reads `0.2.32-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.31` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Generated notes with `./scripts/generate-release-notes.sh generate --version 0.2.31` (1 commit since v0.2.30); bumped `<revision>` to `0.2.32-SNAPSHOT`. No editorial summary added.

## Deviations / notes
- `scripts/check.sh` SKIPPED `./mvnw -B test` (no build inputs in diff: CHANGELOG.md + revision-only pom.xml + record). An uncommitted-tree run of `scripts/check.sh` fail-closed into a full `./mvnw -B test` with 4 pre-existing environment-related failures (ProjectCheckoutServiceTest x3, ProjectAgentSessionWebSocketIntegrationTest x1), unrelated to this diff.
