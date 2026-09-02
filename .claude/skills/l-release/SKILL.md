---
name: l-release
description: Cut a release with one command — gates on the version, drives the single release task (changelog section plus snapshot bump) to a merge-and-dispatch confirmation, then dispatches and verifies the release. Use when the human asks to cut, release, or ship a version.
---

# Cut a release: /l-release <version> [<next-version>]

`<version>` is the bare version to cut (e.g. `0.1.0`, for tag `v0.1.0`). The optional
`<next-version>` overrides the default patch-increase snapshot bump (`0.1.0` →
`0.1.1-SNAPSHOT`); give it a bare version too (`/l-release 0.1.0 0.2.0` bumps to
`0.2.0-SNAPSHOT`).

This is locklane's own second explicitly-invoked exception to
[ADR-001](../../../docs/adr/001-phase0-delivery-workflow.md) D1's "nothing
auto-chains" — [ADR-109](../../../docs/adr/109-one-release-task-one-stop-and-build-only-on-build-inputs.md)
(superseding [ADR-106](../../../docs/adr/106-l-release-single-command-release.md) D1
and D2) records why, alongside `/t-drive`'s own. **One `/l-release <version>` invocation
is the human's ask covering every write the single `/t-drive` call below makes for its
own chained stages** (`AGENTS.md` §Conventions), exactly as a bare `/t-drive <id>`
invocation covers its own chain. This skill composes `/t-open`, `/t-drive`, and
`scripts/release.sh`; it never reimplements any of them, and it never touches
`.github/workflows/release.yml` or `scripts/generate-release-notes.sh` beyond running
them exactly as `docs/architecture/releasing.md` already documents.

## Procedure

0. **Ask, before anything else, if either argument is missing.** `<version>` is
   required and `<next-version>` is optional — but "optional" means the human may
   leave it off to accept the default, never that the skill fills either in on its own
   guess:
   - `<version>` missing → stop and ask for it. Reading `pom.xml`'s `<revision>` and
     suggesting the version it implies (stripping `-SNAPSHOT`) as a default to
     *confirm* is fine; treating that suggestion as given and proceeding without the
     human confirming it is not.
   - `<next-version>` not given → ask whether the human wants the default patch bump
     or a specific override, and confirm the answer here — the same "suggestion, not a
     silent decision" principle `docs/architecture/releasing.md` § Non-goals already
     states for the bump default, applied at the entry point.

   Both arguments resolved — given directly, or confirmed here — before continuing to
   Step 1. `<bump-version>` below is `<next-version>` when given; otherwise the default
   patch increase of `<version>` (`0.1.0` → `0.1.1`).

1. **Gate, before any write.**
   ```bash
   scripts/release.sh gate <version>
   ```
   Exit 0 → continue. Non-zero → **stop loudly, create nothing**, and relay the
   script's one-line reason verbatim — it names the actual `<revision>` and what was
   expected, or the existing `v<version>` tag/release (a released version is immutable,
   `docs/architecture/releasing.md` § Immutability — pick a new version and re-run).

2. **Open the release task.** `/t-open`, producing one task issue whose body reads
   correctly on its own even if someone runs it by hand later:

   ```markdown
   ## Goal
   Cut release v<version>: land its CHANGELOG.md section on main together with the
   `<revision>` bump to `<bump-version>-SNAPSHOT` for the next development cycle.

   ## Done when
   - `./scripts/generate-release-notes.sh generate --version <version>` has been run
     and `CHANGELOG.md` has a `## v<version>` section.
   - `pom.xml`'s `<revision>` reads `<bump-version>-SNAPSHOT`.
   - Both are reviewed and merged to `main` in this one PR.

   ## Scope
   `CHANGELOG.md`, `pom.xml`

   ## Non-goals
   - Does not dispatch the Release workflow — that happens once this PR merges
     (`/l-release`, after this task's merge gate, with `<version>` as the workflow's
     input; the bumped `<revision>` on main is never what the release builds as).
   ```

   State `<bump-version>` plainly in the issue and in this run's own report — the
   default is a suggestion, not a silent decision (`docs/architecture/releasing.md`
   § Non-goals); a human overrides it by passing `<next-version>`, or by editing the
   task's PR before its gate.

3. **Drive the release task.** `/t-drive <id>` (solo mode, ADR-006): plan-if-needed,
   work, review-if-needed, chained into `/t-ship`'s merge-confirmation gate exactly as
   ADR-006 D3 describes. Neither `CHANGELOG.md` nor `pom.xml` is a protected surface,
   so the ordinary case is work straight into the gate. The diff's only `pom.xml`
   change is the `<revision>` line, so `AGENTS.md` §Checks item 1 skips Maven locally
   and in the PR's CI (`scripts/build-inputs.sh`); the push to `main` after the merge
   and the Release build itself still run the full build.

   **At that gate, extend `/t-ship`'s own evidence and question — do not replace
   them** — to also name the dispatch this confirmation authorizes (ADR-109 D1, the
   same "fold the enabling action into the one gate that authorizes it" shape
   `/t-ship` Procedure step 3/5 already uses for its own branch-protection flip):

   - evidence: everything `/t-ship`'s own gate already states, plus: `release
     v<version> will be dispatched and published immediately after this merge, and
     main will then build toward <bump-version>-SNAPSHOT`.
   - question: `"Merge PR #<pr> into main, and publish release v<version>?"`
   - options: unchanged — `confirm` / `abort`.

   **`abort` stops `/l-release` entirely here** — no dispatch. `confirm` merges the PR
   (`/t-ship`'s own Procedure) and authorizes step 4. This gate is the run's one and
   only stop.

4. **Dispatch and verify, immediately after the merge.**
   ```bash
   scripts/release.sh dispatch <version>
   ```
   The script runs `release.yml` on `main` with the version input, watches that run to
   its conclusion, then confirms release `v<version>` exists with a body equal to the
   `CHANGELOG.md` section now on `origin/main`. Exit 0 → continue. **Non-zero → stop
   and report the script's one-line reason verbatim** (a red run and its URL, a run
   that never appeared, a body that does not match, a Release run already in progress
   when it was invoked) — never report a release as cut on a dispatch that cannot be
   confirmed to have succeeded. The merge already landed; nothing is undone.

5. **Stop and report.** The task's number and PR, the published release URL, the
   `<revision>` now on `main`, and — if the gate was answered `abort` — that nothing
   merged and nothing was dispatched.

## Rules

- Never dispatch `release.yml` before step 3's gate is confirmed — the confirmation is
  what authorizes it, not the PR merging by itself.
- Never dispatch by hand what `scripts/release.sh dispatch` does; a defect in the
  script is its own issue, and the fix goes there, not into this skill's prose.
- Never choose `<bump-version>` silently past the stated patch default; a human
  overrides it by passing `<next-version>`, or by editing the task's PR before its
  own gate.
- Never touch `.github/workflows/release.yml` or `scripts/generate-release-notes.sh` —
  run them exactly as documented; a defect in either is its own issue, not a drive-by
  fix here.
