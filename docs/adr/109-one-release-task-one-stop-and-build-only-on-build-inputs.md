# ADR-109: One release task, one human stop, and Maven only when the diff touches a build input

**Status:** Accepted · 2026-09-02
**Deciders:** project owner *(solo phase)*

This ADR **supersedes [ADR-106](106-l-release-single-command-release.md) Decisions 1
and 2** — the two-task, two-stop shape of `/l-release` — and leaves ADR-106 D3 (the
composition bound: `/l-release` only ever calls `/t-open`, `/t-drive`, and the
post-gate dispatch, never a shortcut around them) in force. ADR-106's own text is not
edited (append-only, `CONSTITUTION.md` §2.1). It also decides, under `CONSTITUTION.md`
§1.5 and workflow §11.3, when the project's build-and-test check runs at all — a
change to a gate's scope, so it gets ADR-grade rationale rather than a comment.

## Context

Issue #602. Cutting v0.1.12 with `/l-release` took 28 minutes end to end, of which a
few minutes was the agent's own work. Measured on that run:

- **The Maven test suite ran five times** — twice locally (once per task), twice in PR
  CI, once in the Release workflow — for a one-line `CHANGELOG.md` edit and a one-line
  `pom.xml` edit. CI took 6.5 and 4.0 minutes, the Release build 3.5, the local runs
  4.5. None of those runs could have produced a different answer from the one before:
  nothing Maven compiles or tests had changed.
- **The release was two tasks** (ADR-106 D1: the notes task, then the bump task), so
  two CI round trips and two human gates, with 4.7 minutes spent waiting at gates.
- **The mechanical steps were prose** — the version gate, the dispatch, the watch, the
  body verification — that the agent re-derived on every run (4.5 minutes).

ADR-106's two-task shape followed from `release.yml` deriving the version from
`<revision>` on `main`: the bump could not ride in the notes PR, because the dispatch
that followed the merge would then have read the *next* version and released that.
Making the version an explicit dispatch input removes the reason for the split.

## Decision

### D1. `/l-release` opens one task, drives it once, and stops once

`/l-release <version> [<next-version>]` gates (`scripts/release.sh gate`), then opens
**one** task whose PR carries both the `v<version>` section of `CHANGELOG.md` and the
`<revision>` bump to `<bump-version>-SNAPSHOT`, drives it once (`/t-drive`, solo,
ADR-006), and folds the dispatch's authorization into that task's single `/t-ship`
merge-confirmation gate — ADR-106 D1 step 3's wording, unchanged: the evidence names
the dispatch, the question is "Merge PR #<pr> into main, and publish release
v<version>?", the options are `confirm`/`abort`, and `abort` stops the run with
nothing dispatched. After the merge, `scripts/release.sh dispatch <version>` runs the
Release workflow with the version input, watches it, and verifies the published body
against `origin/main`. ADR-106's bump task, second drive, and second stop are gone; a
release costs exactly one human confirmation.

### D2. The release version is an explicit `workflow_dispatch` input; the mechanical steps are a script

`.github/workflows/release.yml` takes a required `version` input (bare `X.Y.Z`) and
no longer reads `<revision>`; the immutability check (refuse an existing `v<version>`
tag *or* release) and the `CHANGELOG.md` section extraction both key off that input
and run before `setup-java`, so a wrong version fails in seconds. The build still
applies `-Drevision=<version>`, so the published jar identifies itself as the release
whatever `main` currently builds toward. `scripts/release.sh` provides exactly `gate
<version>` and `dispatch <version>`, each exiting non-zero with one plain line on the
first failure; `.claude/skills/l-release/SKILL.md` calls those two subcommands and
carries no other copy of their steps.

### D3. Maven runs only when the diff touches a build input, decided in one place

`scripts/build-inputs.sh <base-ref>` is the single definition of what Maven builds:
the root `pom.xml`, `engine/**`, `client/**`, the Maven wrapper, `ci.yml` itself, and
the script. Exit 0 means a build input changed, 1 means none did, 2 means nothing was
decided (an empty or unreadable diff). Both readers call it and neither restates the
set:

- `.github/workflows/ci.yml`'s `checks` job runs `setup-java` and `./mvnw -B test` on
  a pull request only on exit 0 or 2, and skips both steps on exit 1 — the job, and so
  the required `checks` context, concludes green either way. A push to `main` always
  builds.
- `AGENTS.md` §Checks item 1 applies the same rule to the local check against
  `origin/main`, and a skipped run is recorded in the PR's `## Checks run` as
  `SKIPPED (no build inputs in diff)`, never as `PASS`.

**One exclusion:** a root `pom.xml` diff whose only changed lines are the `<revision>`
line is not a build input. That line is the version string the release task bumps; the
Release build overrides it with `-Drevision`, and the push to `main` after the merge
runs the full build regardless. Any other `pom.xml` change is a build input.

## Rationale

- **The two-task shape was a workaround for the version derivation, not a
  principle.** ADR-106 itself explains the split by sequencing ("the dispatch must
  happen after the *notes* task merges and before the *bump* task is even opened");
  with the version passed explicitly, the bump can precede the dispatch on `main`
  without changing what gets released, and the sequencing argument dissolves.
- **One stop is what ADR-006 and `CONSTITUTION.md` §1.1 actually require** — a human
  confirming the merge. ADR-106 D2's second stop was "ordinary and unremarked", which
  is to say it authorized nothing the first had not: the bump's own gate existed only
  because the bump was its own PR.
- **Skipping Maven on a diff Maven cannot see is not weakening the check.** §1.5
  forbids loosening a gate to make work pass; this decision changes *when a gate that
  cannot fail runs*, on the PR only, while the push to `main` and the Release build —
  the two runs whose outcome anyone consumes — still run everything. The rule is
  fail-closed (exit 2 builds; anything not on the list is invisible to Maven by
  construction) and lives in one script so CI and the local check cannot disagree.
- **Excluding the `<revision>`-only diff is the one judgment call**, made so that
  #602's "no more than two Maven runs per release" and "any `pom.xml` change builds"
  reconcile: the release PR bumps exactly that line, and every path that consumes the
  version string (the Release build, the main-push build) still builds.
- **A script over prose** because the four mechanical steps were re-derived, with
  variation, on every run — 4.5 minutes of a 28-minute release — and a step that
  exits non-zero with one line is something `/t-review` can read and the next run can
  trust.

## Alternatives considered

- **Keep two tasks and merely skip Maven.** Rejected: still two CI round trips and
  two human waits for one release; the measured gate waits (4.7 min) were the second
  largest cost after the redundant builds.
- **Filter Maven at the workflow level (`on.pull_request.paths`).** Rejected: a
  workflow that does not run leaves the required `checks` context unreported, and
  branch protection then blocks every docs-only PR; the other steps in that job (record,
  plan gate, title, blockers, consistency) must run on every PR anyway.
- **Restate the build-input list in `AGENTS.md` prose next to the script.** Rejected:
  two copies is exactly the drift #602 Done-when 2 forbids; `AGENTS.md` names the
  script and the script's header carries the list.
- **Count any `pom.xml` change as a build input, accept three Maven runs per
  release.** Rejected: it fails #602's stated bound and rebuilds the same tree the
  main-push run will build minutes later; the exclusion is narrow (one element, root
  pom only) and fail-closed for everything else.
- **Give `release.yml` a dry-run input so the whole workflow can be exercised before
  merge.** Rejected as scope: the fail-fast path (an existing version) can be exercised
  without publishing anything, and the rest is the same build-and-publish that ran
  unchanged before this ADR.

## Consequences / revisit triggers

- A release costs one task, one PR CI run without Maven, one human stop, and two
  Maven runs in Actions: the main-push build after the merge and the Release build.
- `release.yml` dispatched by hand now needs the version typed; the Actions "Run
  workflow" form asks for it.
- `scripts/release.sh` and `scripts/build-inputs.sh` are ordinary (unprotected)
  scripts; the workflow and skill that call them are protected surfaces.
- **Revisit if** a test or build step starts reading `<revision>` in a way a bump could
  break — the exclusion in D3 then has to go.
- **Revisit if** a second required status context is added that depends on the build
  having run (coverage, Sonar on PRs): the skip would then need to satisfy it too.
- **Revisit if** `scripts/release.sh dispatch` cannot cleanly report a failure after
  the merge (the run never appears, or the watch cannot determine its outcome) — the
  fix is better reporting in the script, not a new gate (carried over from ADR-106).
- **Revisit if** a second maintainer joins (`workflow §13` Q9), exactly as ADR-106 and
  ADR-006 already flag for chaining into a gate that then needs another person's
  approval.
