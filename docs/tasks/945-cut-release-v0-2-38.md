# 945 — Cut release v0.2.38
Issue: #945

## Asked
Cut release v0.2.38: land its CHANGELOG.md section on main together with the `<revision>` bump to `0.2.39-SNAPSHOT` for the next development cycle.

## Done when
- `./scripts/generate-release-notes.sh generate --version 0.2.38` has been run and `CHANGELOG.md` has a `## v0.2.38` section.
- `pom.xml`'s `<revision>` reads `0.2.39-SNAPSHOT`.
- Both are reviewed and merged to `main` in this one PR.

## Explicitly not
- Does not dispatch the Release workflow — that happens once this PR merges (`/l-release`, after this task's merge gate, with `0.2.38` as the workflow's input; the bumped `<revision>` on main is never what the release builds as).

## Decisions made along the way
- Next snapshot is the default patch bump (0.2.39-SNAPSHOT), confirmed by the human at /l-release entry.
- Check 1: `scripts/check.sh` skipped `./mvnw -B test` (no build input in diff; the only pom.xml change is `<revision>`).
- The pre-existing `## Unreleased` section in CHANGELOG.md was left as generated; not this task's scope.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-fable-5-1
