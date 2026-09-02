# 602 — Speed up /l-release: skip Maven on docs-only diffs, fold the bump into the notes task, script the mechanical steps
Issue: #602

## Asked
Cutting a release with `/l-release` took about half an hour on v0.1.12, of which only a
few minutes was the agent's own work: the Maven test suite ran five times for a one-line
`CHANGELOG.md` edit and a one-line `pom.xml` edit, the release was two tasks with two CI
round trips and two human gates, and the mechanical steps (version gate, dispatch, watch,
body verification) were prose the agent re-derived each run. Make a release cost one
task, one PR CI run, and one human stop; run Maven only when the diff touches something
Maven builds; and put the mechanical steps in a script the skill calls.

## Done when
1. CI skips the `setup-java` and "Build and test every module" steps in
   `.github/workflows/ci.yml` when every changed file in the PR is outside the build
   inputs, while the `checks` context still concludes green; the step still runs on
   `push: main` and whenever `pom.xml`, a module's `pom.xml`, or a source file changes.
2. `AGENTS.md` §Checks says `./mvnw -B test` is skipped when the diff touches no build
   input, names the same file set as CI so the two cannot drift, and a PR's
   `## Checks run` line records the skip explicitly rather than claiming a PASS.
3. `.github/workflows/release.yml` takes the release version as a `workflow_dispatch`
   input; the immutability check and the `CHANGELOG.md` extraction key off it.
   `/l-release` opens a single task carrying both the `CHANGELOG.md` section and the
   `<revision>` bump, drives it once, folds the dispatch authorization into that task's
   `/t-ship` merge gate, and dispatches with the version input after the merge. A new
   ADR supersedes ADR-106 D1/D2; `docs/architecture/releasing.md` and the `/l-release`
   row in `AGENTS.md` describe the new sequence. `/l-release` still refuses when
   `<revision>` is not `<version>-SNAPSHOT` or `v<version>` already exists.
4. A script under `scripts/` provides `gate <version>` and `dispatch <version>`, each
   exiting non-zero with a plain one-line reason on any failure, and
   `.claude/skills/l-release/SKILL.md` calls them instead of spelling the steps out.
5. The 3.7-minute gap between CI green and merge on PR #599 is explained: the record
   states whether `/t-ship`'s poll or the human was the cause, and (poll) the interval
   to propose upstream, or (human) that nothing changes.
6. The next `/l-release` run completes with one human stop and no more than two Maven
   runs in GitHub Actions (main CI after merge, and the Release workflow's own build).
   A human judges this after merge.

## Explicitly not
- Does not skip tests inside `release.yml`'s own build; it stays a full build-and-test.
- Does not edit `.claude/skills/t-ship/SKILL.md` or any other template-owned content;
  `ci.yml` and `AGENTS.md` change only inside their `<!-- local -->` slots.
- Does not remove or auto-confirm the human's merge gate; the release still stops
  exactly once, at `/t-ship`'s gate (CONSTITUTION §1.1).
- Does not touch `scripts/generate-release-notes.sh` beyond calling it.

## Decisions made along the way
- **A `<revision>`-only `pom.xml` change is not a build input** (plan pin 1, agent at
  `/t-plan`, 2026-09-02; the human was asked to veto before `/t-work` and did not). Done
  when 1 says `pom.xml` triggers Maven and Done when 6 allows at most two Actions runs
  per release, and the release PR now bumps `pom.xml`; the two reconcile only if the
  bump line alone does not count. `push: main` still runs the full build after the
  merge, and the Release build overrides `<revision>` with `-Drevision` regardless.
- **One script defines the build-input set.** `scripts/build-inputs.sh` is called by
  both `ci.yml` and the local check named in `AGENTS.md`; neither restates the list
  (plan pin 1) — the same "one rule in two forms" shape as `protected-paths.sh`, minus
  the second form.
- **`release.yml`'s pre-build refusals moved ahead of `setup-java`** (plan pin 3): the
  tag check, the release check, and the missing-section check all run before a Maven
  minute is spent, which is also what makes the fail-fast dispatch check below possible.
- **`AGENTS.md`'s skip line has its own shape** — `SKIPPED (no build inputs in diff)`,
  never `PASS` — so `/t-review`'s reuse rule (exact command, `PASS`, head sha) can never
  mistake a skip for a passed run.

## Deviations / notes
- **Done when 5, determined at `/t-plan` from the v0.1.12 session transcript** (the
  console session that ran `/l-release 0.1.12`): the `checks` context concluded green at
  17:52:24Z; `/t-ship` step 2's watch (a loop re-reading `gh pr checks 599` every 15 s)
  returned at 17:52:39Z; the merge-gate question "Merge PR #599 into main?" was posed at
  17:52:51Z and answered `confirm` at 17:55:56Z; `gh pr merge` ran at 17:56:03Z and the
  merge landed at 17:56:07Z. Of the 3.7 minutes, 3 min 5 s was the human answering the
  gate, 15 s the poll interval, and the rest the agent's evidence gathering and the merge
  itself. **The human was the cause; nothing changes and nothing is proposed to the
  t-workflow template.** For comparison, the v0.1.13 notes PR #601 went from CI green
  (18:42:00Z) to merge (18:42:30Z) in 30 s through the same watch.
- `release.yml` cannot be exercised end-to-end before merge without publishing; the
  fail-fast path was exercised instead by dispatching the task branch's workflow with
  an already-released version: run 33674614831 (`gh workflow run release.yml --ref
  wip/602-… -f version=0.1.13`) concluded `failure` at step 4, "Refuse a version that
  already exists", with the CHANGELOG extraction, `setup-java`, the build, and the
  release steps all skipped — the input is wired and the refusal costs seconds.
- **Inside a Locklane console, `GH_TOKEN` is an OAuth-app token the
  `haninaguib-devtools` organization restricts**: `git push` over HTTPS through the
  console's credential helper and `gh workflow run` both returned 403 ("OAuth App
  access restrictions"). The branch was pushed over SSH and the dispatch above ran with
  `GH_TOKEN` unset (gh's keyring login). `scripts/release.sh dispatch` inherits
  whatever token the session has; a console session that cuts a release needs one the
  organization accepts — an environment fact to keep in mind, not something this task
  changes (a fix, if one is wanted, belongs to the console's token setup, its own issue).
- `gh --jq` accepts no `--arg`: the first draft of `scripts/release.sh dispatch` passed
  the dispatch timestamp that way and failed at run lookup; the timestamp is now
  interpolated into the jq expression (second commit).
