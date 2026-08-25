# ADR-001: The Phase 0 delivery workflow

**Status:** Accepted · 2026-08-24
**Deciders:** project owner *(solo phase; the heightened approval bar for protected
surfaces is itself still open — workflow §13 Q9 — so this ADR is explicitly queued for
review if a second maintainer joins.)*

This is the **baseline decision**: the workflow's starting rules and their rationale,
recorded in one place before first use. Future changes are new, append-only ADRs from
here on.

## Context

One person, one repository containing only the delivery system, no application and no
data yet. The workflow must be cheap enough that a solo operator actually uses it —
a process too expensive to follow gets routed around — while keeping the two guarantees
that make an AI-written repository safe to leave alone: durable intent before any diff,
and a human in front of every merge. Backends are pluggable: skills reach the issue
tracker and the code host only through the named operations in `docs/adapters/`
(GitHub for both by default).

## Decision

### D1. Two invariants; every other stage is chosen per task

Work starts from a tracker issue, and `main` moves only by a PR a human confirmed —
never optional. Plan (`/t-plan`), worktree (`/t-wtree`), and independent review
(`/t-review`) are invoked by name when they pay, with one exception: **a protected
surface (`CONSTITUTION.md` §3) always gets a plan before work and an independent review
before shipping**, determined from the paths the diff touches, never from a label.
There are no lanes or per-task categories, no attempt/cycle limits, and **nothing
auto-chains**: each stage ends by naming the next command and stopping.

### D2. The no-issue fix path (`/t-fix`)

Changes with **no semantic content** — typo, spelling, punctuation,
whitespace/formatting, a broken link target — ship as a single PR with no issue, no
record file, and no cold review; the human's read of the diff at the merge gate is
the review. Hard eligibility, refused otherwise: no meaning change; no protected
surface; at most 2 files and ~10 changed lines; one fix per PR; in code, only comments
or human-facing text with zero behavior change. When in doubt, it is not this path.
Anti-creep: `/t-status` counts these merges and retros sample them; one that changed
meaning tightens the gate. Ride-along clarification: a pure typo/format fix in a file
already inside an active task's scope belongs to that task and its record, not here.

### D3. Cancellation rules

`/t-cancel` is the pipeline's only non-merge exit. It records the reason and every
neighbour disposition in the issue's close comment, obtains the human gate before
destroying anything, then closes the PR unmerged, removes a *clean* worktree, deletes
the branch, and closes the issue. A record already merged stays; one that only existed
on the destroyed branch is gone by design. Five safety rules:

1. **The escape hatch is "did a decision happen".** Reasoning that durably constrains
   future work — or an idea opened and dropped repeatedly — is promoted to an ADR or
   `docs/architecture/`, never buried in a close comment.
2. **Cancelling never satisfies a dependency.** Every issue carrying `Blocked-by:` the
   cancelled task gets an explicit disposition — proceed, re-point, or cancel too —
   before the cancellation completes.
3. **Cancelling never cascades silently.** A child's parent initiative is re-judged out
   loud; an initiative's children are decided one by one (cancel, or promote to
   standalone); spun-off Non-goals issues are re-pointed or cancelled, never orphaned.
4. **A `/t-fix` change has nothing to cancel.** No issue, no record, no dependents:
   close the PR, delete the branch, done.
5. **Cancellation is terminal, not deferral.** "Not yet" is an open issue with a
   `Blocked-by:` line; a returning idea is a new issue; undoing merged work is a revert
   — ordinary forward work.

### D4. Task-record paths: ID buckets of 100

Every task carries a record file, created on the branch by `/t-work` from
`docs/tasks/TEMPLATE.md` and merged atomically with the change it describes. It lives at
`docs/tasks/<bucket>/<id>-<slug>.md`, where **`<bucket>` is the task ID rounded down to
the nearest 100, zero-padded to 6 digits** — task 7 → `docs/tasks/000000/7-….md`,
task 142 → `docs/tasks/000100/142-….md`, task 7031 → `docs/tasks/007000/7031-….md`.
(`000000` is not a special case: it is simply the bucket for ids 1–99, which is every
task in a new project's first weeks.) A non-numeric tracker key computes the
bucket from its numeric part and keeps the full key, lowercased, in the filename —
`PROJ-142` → `docs/tasks/000100/proj-142-….md`. A bucket holds at most 100 files regardless
of how fast tasks are opened, and the path is computable from the ID alone. If the
tracker's numbering ever restarts (account loss), the new tracker's counter is bumped
past the old maximum (workflow §10), so IDs — and therefore paths — never collide.

### D5. One home per fact

The skills are the instructions and are self-contained; `docs/workflow.md` carries
cross-stage shape only and no skill cites it. Confirmation-gate rules live once, in
`docs/architecture/confirmation-gates.md`. Anything durable settled in a PR thread is
promoted into the record, an ADR, or the docs before merge — threads are not storage.

### D6. Enumeration never silently truncates

Any scan over issues (scope overlap in `/t-plan`, dependent sweep in `/t-cancel`,
`/t-status` listings) must be complete per `tracker:list-open`'s contract; a result at a
page limit is reported as an incomplete scan, never as "none found".

## Rationale

- **The two invariants carry almost all the safety.** Issue-first gives every change a
  durable statement of intent that predates the diff; human-confirmed PR-only `main`
  means nothing reaches the trunk unseen. Nothing else in the pipeline is needed for
  those guarantees, so everything else is priced per task.
- **Ceremony concentrates where it pays.** Protected surfaces — the constitution, the
  ADRs, the skills, the adapters, the forge config — are where a bad merge corrupts the
  system that produces all other merges, so they keep the full treatment, keyed on
  paths so no judgment can quietly exempt one.
- **Pricing trivial fixes honestly protects the norms that matter.** A workflow that
  charges ten minutes for a one-word typo teaches people to route around it; `/t-fix`
  keeps `main`-by-PR-only intact on exactly the diffs where dropped ceremony verifies
  nothing.
- **Cancellation rules prevent the expensive failure, cheaply.** The dependent rule
  (D3.2) is the one that stops an agent from confidently building on an abandoned
  premise; the anti-cascade rule keeps value that is real on its own; the escape hatch
  keeps decisions out of threads. None of them require a merged artifact, which keeps
  opening speculative work cheap.
- **Bucket sharding is rate-proof.** Year sharding bounds directories by time and fails
  under volume; a 100-ID bucket is bounded by construction, needs no clock, and the
  restart-collision concern is already answered by the counter bump.
- **A rule the agent cannot enforce is worse than no rule** — hence no attempt limits,
  no lanes, and no auto-chaining: a stage invoked by name is a stage someone chose.

## Alternatives considered

- **Mandatory review on everything** — rejected: reviews of trivial diffs get read
  quickly and rubber-stamped, teaching that gates can be satisfied without being
  exercised. Mandatory-on-protected-surfaces keeps the gate honest where it bites.
- **Direct commits to `main` for typos** — rejected outright: unenforceable as an
  exception and breaks the first invariant.
- **A record-only PR preserving cancelled work's story on `main`** — considered and
  deliberately not adopted for Phase 0: the close comment plus the closed PR's diff is
  account enough while the repo holds no data. Revisit trigger 4 below reopens this.
- **Year (or year-month) sharding for records** — rejected: bounds by time, not rate; a
  hot month still produces an unbounded directory. Buckets of 100 are bounded by
  construction.
- **Flat `docs/tasks/`, no sharding** — rejected: unbounded directory over the
  project's life.
- **Suspending the workflow until the application exists** — rejected: the delivery
  system is the thing being built, and exercising it is the evidence of whether it
  works.

## Consequences / revisit triggers

Accepted knowingly: a task shipped without review has had exactly one pair of eyes on
it (the human's, at the merge gate); a cancelled task's story lives on the tracker
and forge, not on `main`.

**The dogfooding phase is the expensive one, and that is the deal.** Until application
code exists, the protected-surface list covers nearly every file here — the skills, the
adapters, the constitution, the scripts, CI. So essentially every change to this
repository demands a plan, a cold review, and a confirmed merge, and the
"ceremony-priced-per-task" benefit this ADR argues for is felt by almost nothing. That is
the intended shape rather than a flaw: this *is* the system that produces every other
merge, and it earns the full treatment. But a reader should expect the first weeks to
feel heavier than the steady state, and should not conclude the pricing rule is broken
when the answer keeps coming back "protected".

Any of these reopens this decision, as a new ADR:

1. **A second person starts implementing** — optional worktrees and optional review
   were both justified by a single implementer.
2. **A defect reaches `main` that an independent review would plausibly have caught.**
3. **The application starts holding real data** — the protected-surface list gains
   application paths and the ceremony balance is recalibrated.
4. **Three or more cancellations at implementation stage or later** — the close comment
   is then the only account of substantial discarded effort; reconsider a record-only
   cancellation PR.
5. **A `/t-fix` merge is found to have changed meaning** — tighten or narrow D2's gate.
6. **A bucket directory approaching its 100-file bound proves unwieldy anyway** — a
   different bucket size is a new ADR, not an edit here.
