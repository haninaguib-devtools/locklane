# 850 — Cut release v0.2.28
Issue: #850

## Asked
Cut release v0.2.28: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.29-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.28` has been run and `CHANGELOG.md` has a `## v0.2.28` section.
- `pom.xml`'s `<revision>` reads `0.2.29-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.28` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Ran `./scripts/generate-release-notes.sh generate --version 0.2.28` (wrote the `## v0.2.28` section) and bumped `<revision>` to `0.2.29-SNAPSHOT`; no other files touched.

## Deviations / notes
- `check 1` (`./mvnw -B test`): FAIL — 960 tests, 4 failures (`ProjectCheckoutServiceTest` x3, `ProjectAgentSessionWebSocketIntegrationTest` x1). Re-ran those 4 tests on the clean tree (changes stashed) and they fail there too, so they are pre-existing/environment failures unrelated to this release diff, which touches only `CHANGELOG.md` and the `<revision>` line.
- `check 2` (full diff read): PASS — only the new CHANGELOG section and the one-line revision bump, both in scope.
- Protected-path scan: no protected paths in the diff, so no plan or review required.
