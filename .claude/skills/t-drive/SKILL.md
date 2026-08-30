---
name: t-drive
description: Drive an initiative's children to completion on an integration branch (merging what review authorizes, excluding what fails one bounded retry, one PR to main), or drive a single ordinary task through its own pipeline — plan, implement, review as the gates require — chained into /t-ship's merge gate; either way the run stops once, at the human's confirmation (ADR-004, ADR-006). Use to drive, run, or autonomously work an initiative or a single task.
---

# Drive an initiative, or a single task

`/t-drive <id>` is the one narrow, explicitly-invoked exception to ADR-001 D1's
"nothing auto-chains", in two modes chosen by the issue's own shape: on an
`initiative`-labeled issue it chains `/t-plan`+`/t-work`+`/t-review` across the
initiative's children without stopping between them, merging what review authorizes
into a shared integration branch, and stops once — at the human's confirmation on a
single PR to `main`; on a plain task it chains that task's own ordinary pipeline —
plan and review only where the gates require them — into `/t-ship`'s
merge-confirmation gate, whose pause is the same single stop (§Solo mode). Optional
(`AGENTS.md`); nothing else in the pipeline invokes it. Resolve every
`tracker:*`/`forge:*` operation via `docs/adapters/TRACKER.md` and
`docs/adapters/FORGE.md` (GitHub by default). The rules are
[ADR-004](../../../docs/adr/004-autonomous-initiative-driving.md) for the initiative
mode and [ADR-006](../../../docs/adr/006-single-task-driving.md) for the solo mode
(amended by [ADR-007](../../../docs/adr/007-solo-drive-defers-on-pending-ci.md) for the
one case where CI has not yet settled); where this skill and those ADRs differ, the ADR
wins — flag it, do not improvise.

## Phase 0 — eligibility, and which mode

1. Read `AGENTS.md`, `CONSTITUTION.md`, and the issue (`tracker:view <id>`). **This read
   covers the whole driven run, in both the initiative mode and solo mode** — every
   chained stage's own read step below names the condition under which it may treat this
   one as already done, and this is the read that satisfies it. **Refuse**
   a closed issue — nothing to drive; say so. Then fork on the `initiative` label:
   labeled → the initiative mode, steps 2–3 below and Phases 1–3, exactly as ADR-004
   defines them; a plain task → **skip steps 2–3 and go to §Solo mode** (ADR-006 D1).
   A tracking issue that merely *looks* like an initiative (children, no label) is a
   tracker defect to report, not a judgment call to make here.
2. `tracker:list-children <id>` — every sub-issue, with state. **Refuse** an initiative
   with no open children — nothing to drive; say so.
3. `tracker:list-open` **once**, filtered client-side to this initiative's open
   children — the same bulk `blockedBy`-bearing call `t-status` already relies on
   (ADR-003), not a `tracker:list-blockers <child-id>` loop: one round trip for the
   whole dependency graph instead of one per child. Build the graph from each child's
   `blockedBy` field, and note any blocker outside the initiative (Phase 2 step 1
   handles both cases). A child already closed (merged in an earlier `/t-drive` run, or
   cancelled) is done; report it and skip it below.

## Phase 1 — the integration branch

1. Resolve `wip/<initiative-id>-integration` idempotently — the same idempotent
   resolution `/t-work` Phase 1 step 4 uses for a task branch: `git fetch --prune`, then
   local and `origin/` refs matching that exact name.
   - Exists → reuse it (a resumed drive).
   - Missing → create it from a current `main`, fast-forwarding a behind-only `main`
     first and refusing on anything ahead or diverged (the same rule `/t-work` applies to
     its own branch creation), then push it
     (`git push -u origin wip/<initiative-id>-integration`) so children have a base to
     target.
   - More than one candidate → stop and report every one; never choose lexically.

   Never commit to this branch directly — every change on it arrives only through a
   child's merge (Phase 2 step 5).

## Phase 2 — each child, chained without stopping

Repeat until every child is merged, excluded, or held on an unresolved outside blocker.
Children with no dependency on each other may run steps 1–6 concurrently — each in its
own `git worktree` (two sessions never share a checkout, `AGENTS.md`), one spawned
read-write agent per child, reporting back — while children in a dependency chain run in
topological order, one after another.

1. **Eligibility.**
   - **Blocked by an issue outside this initiative.** `tracker:list-blockers
     <child-id>`, filtered to just the blocker(s) Phase 0 step 3 already flagged as
     outside the initiative, into `.t-workflow/scripts/check-blocker-gate.sh <file>`.
     Exit 1 → excluded immediately (the same blocker-gate refusal `/t-work` already
     enforces), spending no retry. Exit 0 (satisfied, or no outside blocker at all) →
     not excluded on this ground; the bullets below still apply.
   - Blocked by another child of this initiative, not yet resolved → hold; revisit once
     that child's outcome (merged or excluded) is known.
   - Blocked by another child already **excluded** in this run → excluded immediately,
     cascading, spending no retry — report it as blocked-because-excluded, never as its
     own failure (ADR-004 Decision 2).
   - Otherwise → eligible, continue.
2. **Plan, if needed.** If the child's declared scope touches a protected path
   (`.t-workflow/scripts/protected-paths.sh`) and its issue carries no `## Plan` section, run
   `/t-plan <child-id>` — this is `/t-drive` resolving it, exactly as ADR-004 Decision 1
   describes. If `/t-plan` itself cannot produce one (it stops with a question — an
   incomplete issue, an unresolved ambiguity) that is the "protected path with no plan
   `/t-drive` can resolve on its own" precondition ADR-004 Decision 2 names: exclude the
   child immediately, spending no retry, and report why.
3. **Branch the child from the integration branch, not `main`.** Before running
   `/t-work`, create `wip/<child-id>-<slug>` — the same derivation `/t-work` Phase 1 step
   4 uses from the issue title — from the integration branch's current tip, and push it.
   `/t-work`'s own idempotent branch resolution then finds exactly this one ref and
   reuses it; its contract is untouched (issue #39's Non-goals) — `/t-drive` only makes
   sure the branch already exists in the right place before asking `/t-work` to use it.
4. **Work.** Run `/t-work <child-id>` (Normal mode — branch, record, implement, check,
   draft PR). `forge:pr-create-draft` has no way to name a non-default base, so the draft
   PR lands against `main` by default — immediately retarget it:
   `forge:pr-set-base <pr> wip/<initiative-id>-integration`.
5. **Review.** Run `/t-review <child-id>` exactly as it already runs standalone — same
   isolation rule, same verdict line, reviewed against the integration branch as its
   base.
   - `readiness: ready` → continue to step 6.
   - `readiness: not-ready` (unresolved blocker/high findings), or a check
     `AGENTS.md` §Checks names fails → **one bounded retry** (ADR-004 Decision 2), no
     more: run
     `/t-work <child-id>` again in its own Fix mode (already defined — addresses only the
     named blocker/high findings, no new retry machinery here), re-run only the checks
     the fix falsifies, then `/t-review <child-id>` again, scoped to the fix.
     - Passes this time → continue to step 6.
     - Fails again → **excluded**. Leave its branch and PR exactly as they are — open,
       unmerged, based on the integration branch — the issue stays open and untouched.
       Never auto-merge it, never auto-cancel it. Record the excluding finding or check
       by name for the closing report, then continue to the next child.
6. **Merge, once review authorizes it.** A `readiness: ready` review is what authorizes
   merging into the integration branch — never into `main` (ADR-004 Decision 1):
   `forge:pr-ready <pr>` (a draft PR cannot merge — `/t-work` always opens one, and only
   `/t-ship` marks its own PR ready; on this branch `/t-drive` does that job itself),
   then squash the child's PR into the integration branch with one commit, in the
   ordinary single-task shape — subject `[<child-id>] <issue title>`, body built from
   that child's own record exactly as `/t-ship`'s Procedure step 3 builds one for an
   ordinary task, ending with that child's own `Task: #<child-id>` line
   (`forge:pr-merge <pr> <subject> <body>`, base `wip/<initiative-id>-integration`).
   `/t-drive` performs this merge itself — `/t-ship` only ever merges to `main`. Then
   re-evaluate any child that was held on this one (step 1).

## Phase 3 — the aggregate PR to `main`

Once every child is merged, excluded, or excluded by cascade — nothing left eligible or
held:

1. Open the single PR from `wip/<initiative-id>-integration` to `main`
   (`forge:pr-create-draft` — this one already lands against `main` by default, no
   retargeting needed): title `[<initiative-id>] <initiative title>`; body naming every
   included child and every excluded one with its reason, so the PR reads on its own
   before anyone opens it — plus, **exactly one line per included child, in exactly this
   form and nothing else on the line**: `Task: #<id> — docs/tasks/<bucket>/<id>-<slug>.md`
   (ADR-004 Decision 3, never a blended paragraph). This exact shape is load-bearing, not
   cosmetic — `.github/workflows/ci.yml`'s `record` job parses these lines verbatim
   (`.t-workflow/scripts/check-record.sh --multi`) to find each included child's record; a
   differently-worded or bulleted mention does not satisfy it. Separately, when the
   tracker auto-closes on merge (`tracker:auto-close-on-merge`), also include one
   `Closes #<child-id>` line per included child — the `Task:` line points at that
   child's record for the CI gate above; it is not the forge's own closing keyword, so
   without a `Closes #<child-id>` line of its own a merged child's issue stays open.
   **Never write `Closes #<initiative-id>`** — the initiative issue stays open on
   merge; `/t-ship`'s own closing step asks the human whether to close it, separately,
   once every child's own disposition is known (a driven run may still have an excluded
   child left to decide).
2. Run `/t-review <initiative-id>` on this PR — **an ordinary review, the same bar, the
   same required `cold-review` CI check as any other protected-surface PR** (ADR-004
   Decision 1's own framing: no special-casing, no substitute). It reviews the
   initiative's full combined diff, not only what each child's own review already saw.
   - `readiness: ready` → **stop.** Report every included child, every excluded child
     (and anything held because of one) by number, the PR, and name
     `/t-ship <initiative-id>` as the next command. `/t-drive` never merges to `main`
     itself.
   - `readiness: not-ready` → one bounded retry on the aggregate PR, the same shape as a
     child's (Phase 2 step 5), but performed by `/t-drive` directly rather than via
     `/t-work <initiative-id>` — an initiative issue has no branch or record of its own,
     and `/t-work` refuses one outright. Address only the named blocker/high findings —
     a code fix goes directly onto the integration branch, pushed; a defect in the PR's
     own title or body (its `Task:`/`Closes:` lines, its included/excluded summary) is
     fixed there instead, no commit needed — re-run the checks the findings falsify,
     then re-review.
     - Passes this time → proceed as `readiness: ready` above.
     - Fails again → **stop.** Report exactly what is still blocking, and do not name
       `/t-ship` — the human decides how to proceed from here.

## Solo mode — one ordinary task, chained into the gate (ADR-006)

A plain task from the Phase 0 fork runs its own ordinary pipeline, each stage by its
existing contract, chained without stopping — no integration branch, no autonomous
merge, every gate exactly where the manual pipeline fires it:

1. **Eligibility.** `tracker:list-blockers <id>` into
   `.t-workflow/scripts/check-blocker-gate.sh <file>` — the same gate `/t-work` Phase 1
   step 2 runs. Exit 1 → **stop immediately**, spending no retry: an unsatisfied or
   abandoned blocker is a precondition no fix pass changes (ADR-006 D4).
2. **Plan, if the declared scope needs one.** Run the paths the task's Scope (or
   existing `## Plan`) names through `.t-workflow/scripts/protected-paths.sh`. Protected
   and no `## Plan` section → run `/t-plan <id>` — the invocation covers this write
   (ADR-006 D6). If `/t-plan` stops with a question only a human can answer, **stop
   immediately**, spending no retry, and report why. Not protected → no plan; continue.
3. **Work.** Run `/t-work <id>` (Normal mode) exactly as it runs standalone: ordinary
   branch from `main`, record, implement, checks, draft PR against `main`. No
   retargeting — the Phase 2 base-retargeting step is initiative-mode machinery with no
   counterpart here.
4. **Review, if the actual diff needs one.** Run the diff's real paths —
   `git -c core.quotePath=false diff --name-only main...HEAD` — through
   `.t-workflow/scripts/protected-paths.sh --stdin`. Protected → run `/t-review <id>`
   exactly as it runs standalone. Not protected → **skip review, deliberately**
   (ADR-006 D5): with no autonomous merge anywhere in this mode, review keeps its
   ordinary constitutional role, required for protected surfaces only; note the skip in
   the report so the human can still ask for a cold read by hand. These are two
   independent protected-path tests at two moments — declared scope in step 2, actual
   diff here — never one "did it need planning" flag: a diff that strayed onto a
   protected path is reviewed even when the declared scope looked clean.
5. **One bounded retry.** `readiness: not-ready`, or a check `AGENTS.md` §Checks names
   fails → exactly one `/t-work <id>` Fix-mode pass (only the named blocker/high
   findings), re-run only the falsified checks, then `/t-review <id>` again scoped to
   the fix — the bound and shape ADR-004 Decision 2 defines, no new machinery.
   - Passes → continue to step 6.
   - Fails again → **stop without shipping.** Name the blocking finding or check;
     leave branch, PR, and issue exactly as they are — open, unmerged, untouched — for
     an ordinary human pickup (`/t-work` fix pass, `/t-cancel`, or a re-plan). The
     solo analog of exclusion: never auto-merged, never auto-cancelled.
6. **CI must be settled before spending the run's one stop on the gate** (ADR-007).
   Resolve the task's PR (`forge:pr-find-by-task <id>`) and read its checks
   (`forge:pr-checks <pr>` — `gh pr checks <pr> --json name,bucket`; exit code 8 means
   at least one is still pending). **Any check still queued or in progress
   (`bucket: pending`) → stop here, without invoking `/t-ship`.** Name the pending
   check(s) plainly, say this is not a failure — CI has not finished yet — and name
   `/t-ship <id>` as what to run once it has; this is a single, one-time look, never a
   poll-and-wait loop with a timeout to calibrate. No CI configured, or every check has
   already concluded (green or red) → continue to step 7 unchanged: a concluded
   failure is decisive information, not a wait, and `/t-ship`'s own precondition 3
   reports it immediately, exactly as it always has.
7. **Ship — pause at the gate.** Run `/t-ship <id>` and stop at its
   merge-confirmation gate: that pause **is** the run's single stop (ADR-006 D3, as
   amended by ADR-007 for step 6's one exception above), not a stop *before* `/t-ship`
   naming it in the ordinary case — that is the initiative mode's ending, and the
   asymmetry is deliberate. The gate itself is unchanged — same wording, same
   refusals; the human's confirmation there is what makes the merge and the
   issue-close asked-for (ADR-006 D6). If the session ends at the gate, a standalone
   `/t-ship <id>` finishes the job; nothing is lost.

## Rules

- Nothing here substitutes for `/t-plan`'s, `/t-work`'s, `/t-review`'s, or `/t-ship`'s
  own contract — call them exactly as documented, never reimplement their steps.
- Never merge a child into the integration branch without a `readiness: ready` review of
  that child's own diff.
- Never merge the integration branch into `main` — that is `/t-ship`'s job alone, after
  the human's confirmation.
- In solo mode, merge nothing at all — the only merge is the one the human confirms at
  `/t-ship`'s gate, and skipping review is legitimate only when the *actual diff* is
  non-protected, decided by `.t-workflow/scripts/protected-paths.sh`, never by
  recalling that the declared scope looked clean.
- Never auto-cancel an excluded child, or anything held because of one — nor a solo
  task that stopped without shipping — whether that work still happens is a human's
  call, via `/t-cancel` or an ordinary later `/t-work` fix pass, never a side effect
  of a driven run.
- Never weaken a check, a finding's severity, or a gate to keep a child — or the
  aggregate PR — inside the run.
- Report every outcome by issue number in the closing report: merged, excluded (with the
  failing finding or check), and held-then-excluded-by-cascade — never silently.
