---
name: t-drive
description: Drive an initiative's children to completion on an integration branch — plan, implement, and independently review each, merging what review authorizes and excluding what fails one bounded retry — then stop once for the human's confirmation on a single PR to main (ADR-004). Use to drive, run, or autonomously work an entire initiative.
---

# Drive an initiative

`/t-drive <initiative-id>` is the one narrow, explicitly-invoked exception to ADR-001
D1's "nothing auto-chains": once a human invokes it, this one stage chains
`/t-plan`+`/t-work`+`/t-review` across an initiative's children without stopping between
them, merging what review authorizes into a shared integration branch, and stops once —
at the human's confirmation on a single PR to `main`. Optional (`AGENTS.md`); nothing
else in the pipeline invokes it. Resolve every `tracker:*`/`forge:*` operation via
`docs/adapters/TRACKER.md` and `docs/adapters/FORGE.md` (GitHub by default). The rules
are [ADR-004](../../../docs/adr/004-autonomous-initiative-driving.md); where this skill
and that ADR differ, the ADR wins — flag it, do not improvise.

## Phase 0 — is this really an initiative to drive

1. Read `AGENTS.md`, `CONSTITUTION.md`, and the issue (`tracker:view <id>`). **Refuse**
   anything not labeled `initiative` — say so and recommend `/t-work <id>` on the task
   directly.
2. `tracker:list-children <id>` — every sub-issue, with state. **Refuse** an initiative
   with no open children — nothing to drive; say so.
3. For each open child, `tracker:list-blockers <child-id>` — build the dependency graph
   among this initiative's own children, and note any blocker outside it (Phase 2 step 1
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
   - Blocked by an issue outside this initiative, not closed as completed → excluded
     immediately (the same blocker-gate refusal `/t-work` already enforces), spending no
     retry.
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

## Rules

- Nothing here substitutes for `/t-work`'s or `/t-review`'s own contract — call them
  exactly as documented, never reimplement their steps.
- Never merge a child into the integration branch without a `readiness: ready` review of
  that child's own diff.
- Never merge the integration branch into `main` — that is `/t-ship`'s job alone, after
  the human's confirmation.
- Never auto-cancel an excluded child, or anything held because of one — whether that
  work still happens is a human's call, via `/t-cancel` or an ordinary later `/t-work`
  fix pass, never a side effect of a driven run.
- Never weaken a check, a finding's severity, or a gate to keep a child — or the
  aggregate PR — inside the run.
- Report every outcome by issue number in the closing report: merged, excluded (with the
  failing finding or check), and held-then-excluded-by-cascade — never silently.
