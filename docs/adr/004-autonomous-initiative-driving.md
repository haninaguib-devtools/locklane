# ADR-004: Autonomous initiative-driving via /t-drive

**Status:** Accepted · 2026-08-28
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–003 if a
second maintainer joins — workflow §13 Q9.)*

This ADR does not supersede any of ADR-001's numbered decisions. D1's rule — "nothing
auto-chains: each stage ends by naming the next command and stopping" — stands for every
stage and every task exactly as ADR-001 states it. This ADR adds exactly one,
explicitly-invoked, opt-in exception: when a human runs `/t-drive <initiative-id>`,
that one stage chains `/t-plan`+`/t-work`+`/t-review` across an initiative's children,
performs the merges Decision 1 below authorizes, and then takes the initiative's
combined result through an ordinary `/t-review` of its own — without stopping to ask
"should I continue?" at any of those points. `/t-drive` itself still stops once, exactly
as D1 requires, when it hands that reviewed, combined result to `/t-ship` for the
human's one confirmation. Nothing here touches D1 for a task run any other way.

## Context

Issue #39 asks for a way to work an entire initiative's child tasks to completion
without a human between them, while keeping the human's confirmation at exactly one
point: where the combined result reaches `main`. Today every task reaches `main` as its
own PR, confirmed on its own at `/t-ship`'s gate. Chaining `/t-work`+`/t-review`+`/t-ship`
across an initiative's children back to back would ask for one confirmation per child —
the opposite of "stop once" — and skipping a confirmation to avoid that would break the
human-confirmed-PR-only-`main` invariant that neither ADR-001 nor #39 is willing to
touch. `CONSTITUTION.md` §1.4 also assumes one task per squash commit; a single PR
closing an initiative's several children needs more than one `Task: #<id>` line to name
what it closes.

This ADR is issue #40, blocking the implementation task #41. Its job is to settle the
integration-branch model, the bounded self-correction policy for a child that fails, and
the commit-message extension precisely enough that #41 implements against it without
re-deciding any of the three.

## Decision

### 1. One integration branch per driven initiative; children merge into it, not `main`

`/t-drive <initiative-id>` creates and works from one branch per driven initiative,
`wip/<initiative-id>-integration` — the same `wip/<id>-<slug>` shape task branches
already use, keyed to the initiative issue's own number, with a fixed `integration`
slug rather than one derived from a title. It is branched from a current `main` when the
drive starts, fast-forwarding a behind-only `main` first and refusing on anything
ahead or diverged — the same rule `/t-work` already applies to its own branch creation.

Each child task branches from the integration branch instead of `main`, using its
ordinary `wip/<child-id>-<slug>` name, and goes through `/t-plan` (when the child touches
a protected path) and `/t-work` exactly as today — branch, record, implement, check —
except the child's draft PR is opened against the integration branch as its base, not
`main`. Each child then gets its ordinary independent cold review (`/t-review`), read
against that same base. A review that returns `readiness: ready` is what authorizes that
child's merge — but only into the integration branch, never into `main`. That review was
already, on its own terms, the bar `CONSTITUTION.md` §3 accepts as sufficient for a
protected surface pending a human's confirmation at the next merge; this decision spends
that same, already-accepted authority one merge earlier, on a branch that is not `main`,
which is what lets `/t-drive` proceed to the next child without a human in between.
`/t-drive` performs each authorized merge itself (squashed, one commit per child, each
carrying that child's own `Task: #<id>` line per Decision 3) and moves on.

Once every included child (Decision 2 decides which children are included) is merged
into the integration branch, `/t-drive` opens exactly one PR from the integration branch
to `main`. **That PR is not a special case: it goes through `/t-review` exactly as any
other PR would** — the same independent cold review, the same `readiness: ready` bar,
the same required `cold-review` CI status check (`.github/workflows/review-gate.yml`,
`scripts/check-review-gate.sh`) every protected-surface PR already clears — reviewing
the initiative's full combined diff, not only each child's individual diff a moment
earlier. Only once that review reads `readiness: ready` does `/t-drive` stop, naming
`/t-ship` as the next command, unchanged. A human confirms and squash-merges that PR
exactly as any other task's; `/t-drive` never merges to `main` itself, and a child's
earlier review is never treated as a substitute for the final PR's own. `main` still
only moves by a human-confirmed PR that has cleared the same review gate every other
protected-surface PR clears — what changes is that the human is asked to confirm once,
for the initiative's combined result, instead of once per child.

### 2. Bounded self-correction: one retry per failing child, then exclusion — never auto-merged, never auto-cancelled

A child fails its slot in the driven run at one of two points: `/t-review` returns
unresolved blocker/high findings, or a check `AGENTS.md` §Checks names fails. For either,
`/t-drive` runs exactly one self-correction pass, using `/t-work`'s existing Fix mode
(already defined — addresses only the named blocker/high findings) — no new retry
machinery beyond this one bounded step. The falsified checks and the review both re-run
after the fix pass.

- Passes on that one retry → proceeds into Decision 1's merge step, same as a child that
  passed the first time.
- Fails again → **excluded** from this driven run. Its branch and PR are left exactly as
  they are — open, unmerged into the integration branch — the issue stays open and
  untouched, and it is never auto-merged and never auto-cancelled: whether the work
  should still happen is a judgment for a human (via `/t-cancel`, or an ordinary
  `/t-work` fix pass later), not a side effect of a batch run. `/t-drive` names it, and
  the failing finding or check, in its closing report.

A precondition a retry cannot fix — `/t-work`'s own blocker-gate refusal (an unresolved
or cancelled blocker) or a protected path with no plan `/t-drive` can resolve on its own
— excludes the child immediately, spending no retry: running the same fix pass again
does not change a fact that needs a decision outside the diff.

Exclusion cascades along tracker dependencies: a child `blocked-by` an excluded child
cannot itself start — the same blocker gate `/t-work` already enforces — and is excluded
in turn without spending a retry of its own, reported as blocked because of the excluded
child rather than as its own failure. `/t-drive`'s closing report separates the three
outcomes by name: merged into the integration branch (and included in the final PR),
excluded on its own failed retry, and excluded because a dependency was excluded.

### 3. `CONSTITUTION.md` §1.4: one `Task: #<id>` line per included child

§1.4 required exactly one `Task: #<id>` line per squash commit reaching `main`. A driven
run's single `main`-bound PR closes every included child in that one commit, so §1.4
generalizes to **one or more** `Task: #<id>` lines — one per included child, each written
from that child's own task record, not one blended paragraph covering all of them. The
ordinary, single-task path — the overwhelming majority of merges, and every merge
`/t-ship` performs outside a driven run — is unaffected: one task, one record, one
`Task: #<id>` line, exactly as before.

The per-child merges into the integration branch (Decision 1) are not `main`-bound and
so are not what §1.4 as written governs; they still carry one `Task: #<id>` line each,
in the ordinary single-task shape, so the integration branch's own history stays
legible per child before the final squash to `main` collapses it into one commit.

## Rationale

- **The final `main`-bound PR gets no special exemption from review.** It clears
  `/t-review` exactly like any other protected-surface PR does — the same review this
  repository already, mechanically, requires of every PR reaching `main`
  (`scripts/check-review-gate.sh`, a required branch-protection check). `/t-drive` does
  not special-case that gate or invent a substitute for it, and a reviewer gets one more
  look at the initiative's combined diff, not only at each child's individual diff.
- **Reusing the independent review as the merge-authorization mechanism adds no new
  trust primitive.** `/t-drive` does not invent an automated gate more permissive than
  what a protected surface already accepts; it relocates where that same, already-ratified
  authority (`CONSTITUTION.md` §3) is spent for one hop of the chain, onto a branch that
  is provably not `main`.
- **One bounded retry, reusing `/t-work`'s existing Fix mode, adds the least new
  machinery that still tells a real fix pass from a stuck one** — matching #39's Non-goal
  that no retry/attempt-limit machinery exists anywhere outside this one step. Zero
  retries would exclude a child over a one-line review nit a fix pass trivially resolves;
  unbounded retries would let an unattended agent argue with a review indefinitely, the
  exact failure mode a bound exists to prevent.
- **Never auto-merging or auto-cancelling an excluded child keeps both invariants intact
  for that child individually.** It still reaches `main` only through its own
  human-confirmed path, later, and is only ever cancelled by a human's explicit
  `/t-cancel` — `/t-drive` merely declines to include it in this batch, exactly the same
  posture ADR-001 D3 already takes toward cancellation generally: reasoning that should
  constrain future work does not get buried in a side effect.
- **Cascading exclusion along existing tracker dependencies reuses the blocker gate
  `/t-work` already has**, rather than teaching `/t-drive` a second, parallel notion of
  "blocked" — the same native `blockedBy` fields ADR-003 already made the source of
  truth for ordering.
- **Keeping the single-task commit format as the default protects the 99%+ of merges
  `/t-drive` never touches.** Generalizing §1.4 to "one or more" rather than redefining
  the single-task line's meaning means nothing about an ordinary `/t-ship` merge changes.

## Alternatives considered

- **Each child merges straight to `main` as its own PR, driven back to back but still
  confirmed individually** — rejected: does not achieve "stop once" from #39; multiplies
  confirmation fatigue exactly as much as running each task by hand would.
- **Skip the human confirmation for child merges and require it only once at the very
  end, with no independent review gating child merges** — rejected: removes the one
  intermediate check that catches a bad child before it is buried in a much larger,
  much harder to read combined diff, and breaks `CONSTITUTION.md` §3's protected-surface
  bar for any child that touches one.
- **No integration branch — sequence children as ordinary PRs straight to `main`, each
  confirmed** — rejected: the same "stop once" failure as the first alternative, and
  reintroduces the coordination cost `/t-drive` exists to remove.
- **Unlimited retries until a child passes** — rejected outright by #39's Non-goal and by
  ADR-001's existing bias against machinery an agent cannot enforce; an unattended agent
  retrying indefinitely against a real review is precisely what a bound prevents.
- **Zero retries — any failure excludes immediately** — rejected: wastes the common case
  of a one-off review nit or a flaky check that a single fix pass resolves, forcing every
  minor finding into a human's later, separate pickup.
- **Auto-cancel an excluded child** — rejected: failing one driven run's bounded retry is
  not the same judgment as deciding the work should never be done; that decision belongs
  to a human at `/t-cancel`, not to a side effect of a batch run.
- **One blended `Task:` line summarizing all included children in prose** — rejected:
  loses the ability to `grep` `git log` for a specific task's landing commit — the exact
  property the bracketed-id convention exists for — and blurs §1.4's "self-contained"
  bar into a summary that can drift from what each child's own record says.
- **No independent review of the final aggregate PR — treat the children's reviews as
  sufficient and let `/t-ship` merge it directly** — rejected: this repository's
  required `cold-review` CI check gates a merge to `main` on a review of *that* PR, and
  skipping it for the aggregate PR would mean either weakening a guardrail
  (`CONSTITUTION.md` §1.5 forbids that) or special-casing the gate for exactly the PR
  where a reviewer's look at the combined effect of several children landing together
  matters most.

## Consequences / revisit triggers

Accepted knowingly: a driven run's per-child, integration-branch merges have no human
confirmation of their own — the independent review is the only gate between "child
passes" and "child lands in the batch that will reach `main`." This is the deliberate
trade this ADR names, not an oversight: the same authority `CONSTITUTION.md` §3 already
treats as sufficient pending a human's final say, spent consistently rather than doubled
up. The initiative's combined result then gets its own independent review — the same
one any other protected-surface PR gets, satisfying the same required CI gate — right
before the human's one confirmation at `/t-ship`; a human confirming that PR is trusting
both that final review and the reviews that already ran per child, not re-reading each
child's diff line by line unassisted.

Any of these reopens this decision, as a new ADR:

1. **A defect reaches `main` via a driven run that an independent review of that specific
   child plausibly would have caught, yet the review passed it** — ADR-001's existing
   review-defect trigger, now specifically implicating the review-authorizes-merge
   mechanism this ADR adds.
2. **The one-retry bound proves miscalibrated** — nearly every failing child needs a
   second retry, or children are landing after a fix pass that should not have — tune
   the count, not the shape, in a new ADR.
3. **Excluded children pile up faster than a human picks them up** — the
   never-auto-cancelled default may need a durable surfaced prompt beyond `/t-drive`'s
   one-time closing report, rather than relying on that report alone.
4. **A second maintainer joins** — ADR-001 revisit trigger 1, restated here because it
   bears on whether the per-child integration-branch merge should also require a human,
   not only the final `main`-bound one; workflow §13 Q9 decides.
5. **The application starts holding real data and gains protected surfaces beyond
   today's list** (ADR-001 revisit trigger 3) — whether a driven run may include a child
   touching those surfaces at all becomes a live question rather than an inherited
   default.
