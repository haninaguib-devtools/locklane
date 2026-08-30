---
name: t-work
description: Implement a task — check its gates, get onto its branch, write the record, work within scope, run the checks, and open the draft PR. Also runs narrow fix passes. Use when asked to work, start, pick up, continue, or fix a task.
---

# Implement a task

One invocation, start to draft PR. The argument names the issue (`/t-work 154`); with
nothing, list open non-initiative issues and ask. **Refuse a tracking issue**, however
named — an `initiative`-labeled issue coordinates work, it has no branch or record of
its own: say so, list its open children, and recommend `/t-work` on one of them.
Discussing a task is not asking for it to be worked; confirm the human wants
implementation before editing files.

## Phase 1 — gates, before touching any file

1. Read the issue (`tracker:view <id>`, including any `## Plan` section), the tracking
   issue if there is one, and any merged design doc the issue names. Read `AGENTS.md` and
   `CONSTITUTION.md` too — unless this work is `/t-drive` chaining this stage in the same
   session whose Phase 0 already read them for the whole run, in which case that read
   already covers this one. A standalone invocation, with no driving session, always
   reads both itself. Resolve every `tracker:*` / `forge:*` operation named in this skill
   via `docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md` (GitHub by default).
2. **Blockers.** `tracker:list-blockers <id>`, written to a file, then
   `.t-workflow/scripts/check-blocker-gate.sh <file>`. Exit 0 → continue. Exit 1 → stop
   and say so: a blocker **cancelled** rather than completed was abandoned, not
   satisfied, even though it is closed (ADR-001 §D3.2, over the native `blockedBy` field
   plus `stateReason` per ADR-003).
3. **Protected surfaces.** If the work will touch a protected path and the issue has no
   `## Plan` section, stop and recommend `/t-plan <id>`. Decide with
   `.t-workflow/scripts/protected-paths.sh <paths>` (`CONSTITUTION.md` §3 in executable form) over
   the paths the task's Scope names. Applies mid-task too: work that grows onto a
   protected path stops for a plan rather than continuing under a scope that never
   covered it; Phase 3 re-checks it against the real diff.
4. **Get onto the task branch, in the checkout you were invoked from.** A worktree is
   optional (ADR-001, ADR-002) — if the human wanted one, it exists already and this
   session is rooted there. Resolve the branch idempotently — `git fetch --prune`, then
   list local and `origin/` refs matching `wip/<id>-*`, normalizing away the `origin/`
   prefix (ADR-001 §D4):

   - exactly one → reuse it (`git checkout <branch>`, or it is already current), then
     check it against the `origin/main` the fetch above already updated: behind-only →
     rebase onto it (`git rebase origin/main`) when the rebase applies cleanly, noting
     it in the report; a conflict → stop and report, leaving the rebase in progress
     rather than aborting it — never auto-resolve, the human resolves it directly and
     re-invokes `/t-work` once done. The same fast-forward-when-safe,
     stop-when-it-isn't split the `main` refusal below applies to branch creation;
   - none → derive `wip/<id>-<slug>` from the issue title (lowercase, each run of
     non-alphanumeric characters becomes `-`, trim leading/trailing `-`, truncate to 40
     characters, trim a trailing `-` again) and create it from a current `main`;
   - more than one → stop and report every candidate; never choose lexically.

   Four refusals, each reported rather than worked around: **this session is in a
   different task's worktree** (a linked worktree whose registered branch is not this
   task's) → stop, report the primary checkout's path, never switch a checkout
   belonging to another task, even clean; **the branch is checked out in another
   worktree** → report that absolute path and stop, work continues there; **the current
   checkout is dirty with changes that are not this task's** → stop and report them,
   never stash or discard someone else's work; **creating the branch would start from a
   stale or diverged `main`** → fast-forward a behind-only `main` (`git merge --ff-only
   origin/main`), stop and report if ahead or diverged. Never commit on `main`.

5. **Normal or fix mode.** Resolve the task's PR, if it has one, from the branch:
   `forge:pr-find-by-task <id>` — none on a fresh task, exactly one once Phase 3 step 5
   has run, more than one is a stop-and-report. If that PR carries a review
   (`forge:pr-reviews <pr>`) with unresolved `blocker`/`high` findings and the human has
   asked for them to be addressed, read the existing record and go to **Fix mode**
   below. Otherwise continue.
6. **The record.** Create `docs/tasks/<bucket>/<id>-<slug>.md` from
   `docs/tasks/TEMPLATE.md`, where `<bucket>` is the task ID rounded down to the
   nearest 100, zero-padded to 6 digits (task 142 → `docs/tasks/000100/`; ADR-001 §D4),
   filling Asked / Done when / Explicitly not from the issue. Resumed or fix work reads
   the existing record instead; the record is part of the diff and merges with the
   work.

   **Resuming after a re-plan, write the scope change into Deviations before touching a
   file:** what the previous plan allowed, what the new one allows, and why — the text
   `/t-plan` quoted in its report. A re-plan replaces the `## Plan` section, so the
   issue no longer holds the old bounds; if `/t-plan`'s report is not in this session,
   say so and ask rather than reconstructing it from memory.

## Phase 2 — the work

- The Allowed paths (or the issue's Scope line, without a plan) are **binding**. An
  issue carries exactly one `## Plan` section, and it governs — two sections is a
  defect, stop and report it rather than choosing between them. Out-of-scope defects
  are never drive-by fixes, and never issues you open on your own: **propose and
  wait**, describing the issue it deserves (title, what is wrong, the tracking issue it
  belongs under where apt) in the closing report as a recommendation for the human, who
  opens it or asks you to (AGENTS.md §Conventions). Exception (ADR-001 ride-along): a
  pure typo/formatting fix in a file already inside this task's scope may be made here
  and listed in the record.
- Existing behavior in the touched area is protected: preserve it unless the issue
  changes it explicitly, and investigate anything that disappears unexplained.
- Never weaken a check, test, or guardrail to make work pass.
- Update the record as you go: decisions taken along the way (with who and when),
  deviations (each needs the human's approval in the moment — record it), and dead
  ends worth remembering.

## Phase 3 — checks, commit, draft PR

1. Run the checks tagged `implementation` or `either` in the plan, or the set in
   `AGENTS.md` §Checks when there is no plan. Report what actually happened in plain
   prose per AGENTS.md §Communication — never soften a failure into "should work". Note
   each check's exact command and result — step 5 records the ones tagged `either` (or,
   with no plan, this whole set) as this commit's provenance for `/t-review` to reuse,
   sparing it from running the same command again on an unchanged tree.
2. **Read your own diff** (`git diff main...HEAD`) for scope drift, unintended
   deletions, and leftover scratch; remove what does not belong. **An edit here
   invalidates step 1's result for whatever it touched** — re-run any affected check
   before step 5 records anything as this commit's provenance; a check nothing here
   touched needs no re-run. Then re-check protection against what the diff *actually*
   touches — Phase 1 step 3 could only
   judge what the work was expected to touch: `git -c core.quotePath=false diff
   --name-only main...HEAD | bash .t-workflow/scripts/protected-paths.sh --stdin`
   (`core.quotePath=false` matters — by default git quotes and octal-escapes a
   non-ASCII path, and a gate reading the quoted form would see no protected path).
   Exit **0** = protected, **1** = none, **2** = nothing was checked — on a task with a
   diff, that means something is wrong with the command, not a clean diff. A protected
   path here with no `## Plan` on the issue stops the task for `/t-plan <id>` rather
   than opening a PR that `/t-ship` will refuse.
3. **Pinned-consumer local gates.** When `.template-manifest.json` exists at the repo
   root — this repo never does; a repo generated from this template does once it
   adopts — run the same checks a consumer's own CI would run, locally, before pushing,
   so a drift the plan step didn't catch fails here instead of round-tripping through
   CI:
   - `.t-workflow/scripts/check-manifest.sh` — pure local, no tracker call.
   - `.t-workflow/scripts/check-record.sh <id> <record-file>` — pure local, no tracker
     call; `<id>` and `<record-file>` are already fixed, from Phase 1 step 6.
   - `git -c core.quotePath=false diff --name-only main...HEAD | bash
     .t-workflow/scripts/check-plan-gate.sh <issue-body-file>`, `<issue-body-file>`
     being `tracker:view <id>`'s body written to disk (mirrors the `plan-gate` job in
     `.github/workflows/ci.yml`) — re-fetch it first if the issue could have changed
     since Phase 1's read (after a re-plan, say), rather than trusting a stale copy.

   A failure from any of these three is a check failure like any other — fixed before
   continuing, never bypassed (`CONSTITUTION.md` §1.5).
4. Commit on the branch — real messages, imperative, no `wip`, no trailers.
5. Push (`git push -u origin wip/<id>-<slug>`) and open the draft PR
   (`forge:pr-create-draft`) — title: `[<id>] <issue title>`; body: the tracker's
   auto-close phrase for `<id>` when it has one (`tracker:auto-close-on-merge`),
   followed by what changed, what was verified with actual results, and what remains
   open. **Include a `## Checks run` section**, one line per check that is a candidate
   for `/t-review` to reuse — tagged `either` in the plan, or (no plan) named in
   `AGENTS.md` §Checks — each exactly `- \`<command>\` — <PASS/FAIL> — commit \`<sha>\``,
   `<sha>` being `git rev-parse HEAD` for the commit step 4 just made. A check tagged
   `implementation`-only is never listed here — `/t-review` was never going to run it
   itself, so there is nothing for it to reuse.
6. **Stop and report.** Nothing chains from here (ADR-001). Say what the change does in
   ordinary language, what the checks actually returned, and name the next command:
   `/t-review <id>` — **required** when the diff touches a protected surface
   (`CONSTITUTION.md` §3) — otherwise `/t-review <id>` if the human wants a cold read,
   or `/t-ship <id>` directly. Do not mark the PR ready and do not merge — `/t-ship`
   owns that.

## Fix mode

Address **only** the named blocker and high findings. Anything else found is reported,
not acted on: describe it as a further finding, or as an issue you recommend the human
open — never open one yourself (AGENTS.md §Conventions). Medium/low findings are fixed
only when the human asks by number. Append what each change answers to the record's
Deviations / notes, re-run the checks the findings falsify, and push to the same branch
and PR. **Rewrite the PR body's `## Checks run` section wholesale** (never append — the
same replace-not-append rule `/t-plan` uses for `## Plan`), listing only the checks this
pass re-ran, at the new head commit: a check this pass did not touch is simply absent
from the rewritten section, which correctly means `/t-review` runs it itself rather than
trusting a provenance line that now names a superseded commit. Stop and report, naming
`/t-review <id>` for a scoped re-review; if the same findings survive repeated passes,
say so and recommend re-planning rather than trying again.
