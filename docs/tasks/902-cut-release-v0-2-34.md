# 902 — Cut release v0.2.34
Issue: #902

## Asked
Cut release v0.2.34: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.35-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.34` has been run and `CHANGELOG.md` has a `## v0.2.34` section.
- `pom.xml`'s `<revision>` reads `0.2.35-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.34` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Ran `./scripts/generate-release-notes.sh generate --version 0.2.34` (wrote v0.2.34 section, v0.2.33..HEAD, 1 commit); bumped `pom.xml` `<revision>` 0.2.34-SNAPSHOT → 0.2.35-SNAPSHOT.

## Deviations / notes
- `scripts/check.sh` run before committing executed the full `./mvnw -B test`
  (fail-closed: `origin/main...HEAD` was still empty) and 4 token-related tests
  failed (`ProjectCheckoutServiceTest` x3, `ProjectAgentSessionWebSocket...` x1)
  because `GH_TOKEN` is set in this environment while those tests assert
  no-token behavior. Unrelated to this diff (CHANGELOG + `<revision>` string +
  record); reported here, not fixed in passing. After committing,
  `scripts/check.sh` skips Maven per `scripts/build-inputs.sh` as prescribed.

## Agents
- work: opencode / meta/muse-spark-1.3-contributor
