# 847 — Skip the local Maven check when the diff touches no build input
Issue: #847

## Asked
ADR-109 D3 made `scripts/build-inputs.sh` the one definition of what Maven builds and applied it on both sides: the PR's CI skips `./mvnw -B test` when the diff touches no build input (a root `pom.xml` diff whose only changed lines are `<revision>` does not count), and the local check before a PR applied the same script against `origin/main`. Task #839 (t-workflow v0.0.5) dropped the local half: `.t-workflow/AGENTS.md` check 1 now skips only a documentation-only diff, so every release task (`CHANGELOG.md` + the `<revision>` bump) runs the full Maven suite locally and then the Release workflow builds it again. Restore the local skip without touching the template: add `scripts/check.sh`, which runs `scripts/build-inputs.sh origin/main` and then `./mvnw -B test` on exit 0 or 2, and prints a skip line and exits 0 on exit 1 (fail closed, exactly as `.github/workflows/build.yml` does); point the `check` value in `.t-workflow/config` at it. A skipped run must be recorded in the PR's `## Checks run` as skipped, never as PASS. While in those files, fix the references that went stale with the workflow rename and the t-workflow replacement: the comment in `build.yml` and the header comment in `build-inputs.sh` still name the old `AGENTS.md` §Checks item 1 as the local caller, and `build-inputs.sh` both mentions and lists `.github/workflows/ci.yml` as a build input where the workflow that runs the build is now `build.yml`.

## Done when
- `scripts/check.sh` exists, is executable, and on a branch whose diff against `origin/main` changes only `CHANGELOG.md` and the `<revision>` line of `pom.xml` exits 0 without running Maven, printing a line that says the build was skipped and why.
- On a branch whose diff touches any path under `engine/` or `client/`, or on an empty/unreadable diff, `scripts/check.sh` runs `./mvnw -B test` and exits with its status.
- `grep '^check=' .t-workflow/config` reads `check="scripts/check.sh"`.
- `grep -n 'ci.yml' scripts/build-inputs.sh` returns nothing; the patterns list names `.github/workflows/build.yml`.
- `grep -n 'AGENTS.md' scripts/build-inputs.sh .github/workflows/build.yml` returns nothing that names AGENTS.md as the local caller; both comments name `scripts/check.sh` instead.
- `scripts/check.sh` documents how a skip is written in a PR's `## Checks run` line, and `.claude/skills/l-release/SKILL.md` step 3 says the local check skips Maven for the release diff.

## Explicitly not
- Does not change `.t-workflow/AGENTS.md` or anything under `.t-workflow/scripts/`: those are template-owned, and the fix lives in the repository's own `check` slot.
- Does not change which paths count as build inputs beyond correcting the workflow filename.
- Does not touch `.github/workflows/release.yml` or the push-to-main build, which keep running the full suite.

## Decisions made along the way
- `scripts/check.sh` hardcodes `origin/main` as the base ref per the issue, and `exec`s `./mvnw -B test` so its exit status passes through unchanged.
- Skip-line wording follows ADR-109 D3 (`SKIPPED (no build inputs in diff)`) so the `## Checks run` entry reads as skipped, never PASS.
- Verified the skip path in a throwaway worktree on a release-like diff (CHANGELOG.md + revision-only pom.xml): `build-inputs.sh` exited 1, `check.sh` exited 0 in 0.013s without invoking Maven. Bad-ref case exits 2 (fail closed). Build path is exercised by this task's own diff, which touches build inputs.

## Deviations / notes
- none

## Checks run
- `git status --porcelain | awk '{print $2}' | .t-workflow/scripts/docs-only.sh` — exit 1 (not docs-only; non-md paths changed), so check 1 ran.
- `./scripts/check.sh` — FAIL on this branch: it correctly took the build path (this diff touches build inputs) and ran `./mvnw -B test` — 960 tests, 4 failures in `ProjectCheckoutServiceTest` (3, ambient gh-auth leakage) and `ProjectAgentSessionWebSocketIntegrationTest` (1, PT5S timing). The same 4 fail on pristine `origin/main` (verified in a throwaway worktree), so they are pre-existing environment failures unrelated to this diff, which touches no engine/client code. Reported as failure, not weakened.
- Skip-path probe (throwaway worktree, release-like diff): `scripts/check.sh` exited 0 in 0.013s with `No build input in this diff: skipping ./mvnw -B test.` — Maven not invoked.
- `grep '^check=' .t-workflow/config` → `check="scripts/check.sh"`.
- `grep -n 'ci.yml' scripts/build-inputs.sh` → no matches; patterns list names `.github/workflows/build.yml`.
- `grep -n 'AGENTS.md' scripts/build-inputs.sh .github/workflows/build.yml` → no matches; both name `scripts/check.sh`.
