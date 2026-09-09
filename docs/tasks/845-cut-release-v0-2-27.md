# 845 — Cut release v0.2.27
Issue: #845

## Asked
Cut release v0.2.27: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.28-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.27` has been run and `CHANGELOG.md` has a `## v0.2.27` section.
- `pom.xml`'s `<revision>` reads `0.2.28-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.27` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Release notes generated with `./scripts/generate-release-notes.sh generate --version 0.2.27` (1 commit since v0.2.26); `<revision>` bumped to `0.2.28-SNAPSHOT` (default patch bump, confirmed at /l-release entry).
- Local `./mvnw -B test`: 959 tests, 4 failures in `ProjectCheckoutServiceTest` (3) and `ProjectAgentSessionWebSocketIntegrationTest` (1) — all expect no GH credential in the environment, but this session exports `GH_TOKEN`, so the host token leaks into the assertions. Unrelated to this diff (CHANGELOG.md + `<revision>` line only); CI's build-inputs step skips Maven for this PR anyway.

## Deviations / notes
- none
