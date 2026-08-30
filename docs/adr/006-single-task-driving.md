# ADR-006: Single-task driving via /t-drive

**Status:** Accepted · 2026-08-29
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–005 if a
second maintainer joins — workflow §13 Q9.)*

This ADR **extends ADR-004; it supersedes nothing.** ADR-004 carved one narrow,
explicitly-invoked, opt-in exception to ADR-001 D1's "nothing auto-chains": a human
running `/t-drive` on an initiative lets that one stage chain
`/t-plan`+`/t-work`+`/t-review` across the initiative's children. This ADR widens that
same exception — the same command, the same single human invocation as its trigger — to
cover one ordinary, non-initiative task. It is a widening, not a second exception:
nothing else in the pipeline chains, and no stage chains without a human having invoked
`/t-drive`. Every decision ADR-004 makes for initiatives — the integration branch, the
per-child merges, the exclusion policy, the aggregate PR — stands untouched and applies
only to initiatives, exactly as written there.

## Context

Issue #77. `/t-drive` today refuses anything not labeled `initiative` and recommends
`/t-work` on the task directly. Working one ordinary task end to end therefore takes up
to four invocations — `/t-plan`, `/t-work`, `/t-review`, `/t-ship` — with a human
re-prompting between each. Those between-stage stops exist (ADR-001 D1) so that a human
chooses each escalation; but for a single task the choices are already fully determined
by ratified rules: whether to plan is decided by the declared scope against
`.t-workflow/scripts/protected-paths.sh`, whether to review is decided by the actual
diff against the same script (`CONSTITUTION.md` §3), and `/t-ship` is always the exit.
Between the invocation and the merge gate there is no judgment left for the human to
supply — only typing. The one place a human's judgment is genuinely irreplaceable is
`/t-ship`'s merge-confirmation gate, which no mode of `/t-drive` may absorb
(`CONSTITUTION.md` §1.1–1.2).

The constraint the issue fixes up front: **nothing about the trust posture changes.**
No integration branch, no autonomous merge of any kind; every gate fires exactly where
the manual pipeline fires it. The deliverable is this ADR plus the skill and doc edits
implementing it.

## Decision

### D1. Same command; the mode is chosen by the `initiative` label

`/t-drive <id>` reads the issue. Labeled `initiative` → the existing initiative mode,
its phases exactly as ADR-004 defines them, unchanged. A plain task → the solo sequence
(D2). The current Phase 0 refusal of a non-initiative issue becomes this fork; Phase
0's other eligibility checks — the blocker gate and the refusal of a closed issue —
apply identically in both modes. No new command, no flag: the issue's own shape already
says which kind of drive it can be, and a second entry point would only add a
name to document and a way for the two to drift.

### D2. The solo sequence: two protected-path tests, at two different moments

The solo mode runs the ordinary pipeline stages in order, each by its existing
contract:

1. `/t-plan <id>` — only when the task's *declared scope* touches a protected path
   (`.t-workflow/scripts/protected-paths.sh`) and the issue has no `## Plan` section
   yet.
2. `/t-work <id>` — Normal mode, exactly as it runs standalone: ordinary branch from
   `main`, draft PR against `main`. No integration branch and no retargeting — the
   base-retargeting step is initiative-mode machinery and has no counterpart here.
3. `/t-review <id>` — only when the *actual diff* touches a protected path.
4. `/t-ship <id>` — chained into, pausing at its merge-confirmation gate (D3).

Plan-need and review-need are **two independent tests at two different moments** —
the declared scope before any work, the actual diff after it — never collapsed into
one "did it need planning" flag. A diff that strays onto a protected path is reviewed
even when the declared scope looked clean; this is the same re-check `/t-work` Phase 3
and `/t-ship`'s preconditions already perform, executed rather than reinvented.

### D3. Chaining into /t-ship's gate is the single stop

The solo run does not end by naming `/t-ship` — it invokes `/t-ship <id>` and pauses at
that stage's existing merge-confirmation gate. That pause *is* the "stops once for the
human's confirmation": same gate, same wording, same refusal behavior as a standalone
`/t-ship`, reached without the human re-typing the command.

This deliberately differs from the initiative mode, which stops *before* `/t-ship` and
names it as the next command. The asymmetry is stated here rather than left to be
discovered: an initiative's closing report — which children merged, which were
excluded and why — is something a human digests before choosing to open the merge
question at all, so the stop before `/t-ship` carries real information; a solo run has
exactly one task and nothing to digest that its PR does not already say, so a stop
before the gate would be a second confirmation with no judgment in it. Aligning the
initiative mode to chain into the gate as well is a possible future change — a
boundary this ADR names (revisit trigger 5), not work it does.

If the session ends at the gate unanswered, nothing is lost: the branch, draft-turned-
ready PR, and issue are all in the ordinary pre-merge state, and a standalone
`/t-ship <id>` finishes the job.

### D4. One bounded retry, then stop without shipping

A solo run fails its slot at the same two points a driven child does: `/t-review`
returns `readiness: not-ready` (unresolved blocker/high findings), or a check
`AGENTS.md` §Checks names fails. Either buys **exactly one** self-correction pass, in
the shape ADR-004 Decision 2 already defines and with no new retry machinery:
`/t-work <id>` in its existing Fix mode, addressing only the named blocker/high
findings, re-running only the checks the fix falsifies, then a re-review scoped to the
fix.

- Passes → the run proceeds to `/t-ship`'s gate (D3).
- Fails again → the run **stops without shipping**, naming the blocking finding or
  check. Branch, PR, and issue are left exactly as they are — open, unmerged,
  untouched — for an ordinary human pickup (`/t-work` fix pass, `/t-cancel`, or a
  re-plan). This is the solo analog of exclusion: never auto-merged, never
  auto-cancelled.

A precondition a retry cannot fix stops the run immediately, spending no retry — the
same rule ADR-004 Decision 2 states for a child: an unsatisfied or cancelled blocker,
or a protected declared scope for which `/t-plan` cannot produce a plan on its own
(it stops with a question only a human can answer). Running a fix pass again does not
change a fact that needs a decision outside the diff.

### D5. A non-protected diff ships unreviewed — deliberately, and here is why

The initiative mode reviews every child regardless of protection because review there
*authorizes an autonomous merge* into the integration branch — the review is the only
gate between "child passes" and "child lands in the batch", so it must always run. The
solo mode has no autonomous merge of any kind: the human confirms, at `/t-ship`'s
gate, the very PR `/t-work` produced. With that authorizing role gone, review reverts
to its ordinary constitutional position — required for a protected surface
(`CONSTITUTION.md` §3), chosen per task otherwise (`CONSTITUTION.md` §1.2). Requiring
it unconditionally in solo mode would be gate inflation: a ceremony the manual
pipeline never imposed, added exactly where nothing new needs guarding. A human who
wants a cold read anyway runs `/t-review <id>` by hand, before or instead of a solo
drive — nothing here removes that option.

### D6. Tracker-write authority is the invocation plus the gate

The same posture as the initiative mode, stated in the skill rather than implied: the
one `/t-drive` invocation is the human's ask covering every tracker write the chained
stages already make for themselves — `/t-plan` writing the `## Plan` section onto the
issue, `/t-work` and `/t-review` posting on the task's own PR — and the human's
explicit confirmation at `/t-ship`'s merge gate is what makes the post-merge writes
(closing the issue) asked-for, exactly as it does in a standalone `/t-ship`. Nothing
in a solo run writes to any issue other than the driven task's own.

## Rationale

- **Widening an existing exception beats adding a second one.** The trigger, the
  posture, and the justification are ADR-004's own: a human explicitly invokes one
  stage that chains the others. A separate command or flag would create two entry
  points whose documentation and eligibility rules drift; a label-read fork cannot
  drift because the issue's shape is the mode.
- **The two protected-path tests execute existing rules; they invent none.** The
  manual pipeline already decides plan-need from the declared scope (at `/t-plan`
  time) and review-need from the actual diff (`/t-work` Phase 3's re-check,
  `/t-ship`'s precondition). The solo mode runs the same two tests at the same two
  moments — automation of a decision procedure, not a new decision.
- **Pausing inside `/t-ship` is where "stop once" lands for a run whose whole point
  is one stop.** Stopping before it, symmetric with the initiative mode, would make
  the solo run's stop count two human touches (read the report, type the command) for
  zero added judgment — the report of a one-task run says nothing its PR does not.
  The gate itself — the one human judgment — fires unchanged.
- **The retry bound is inherited, not re-derived.** ADR-004 Decision 2 already
  calibrated one bounded pass as the least machinery that tells a real fix from a
  stuck one; a solo run reuses that calibration and its Fix-mode mechanism verbatim,
  honoring the issue's Non-goal of no new retry machinery.
- **Skipping non-protected review is the constitution's own line, not a new
  relaxation.** `CONSTITUTION.md` §1.2 makes independent review per-task except on
  protected surfaces; a solo drive that imposed it unconditionally would quietly
  ratchet the pipeline's cost up in the mode meant to reduce it — and gates added
  without need are how ceremony accretes (the exact drift ADR-002 trimmed).

## Alternatives considered

- **A new command or a flag (`/t-drive --solo`, `/t-auto`)** — rejected: the
  `initiative` label already determines the only sensible mode; a second entry point
  duplicates documentation, invites drift between the two, and makes the human decide
  something the issue's shape decides.
- **Review every solo diff unconditionally, mirroring the initiative mode** —
  rejected: in the initiative mode review authorizes an autonomous merge; solo mode
  has none, so the unconditional requirement would be gate inflation past what the
  constitution asks of the manual pipeline (D5's full argument).
- **Stop before `/t-ship` and name it, symmetric with the initiative mode** —
  rejected: adds a human touch carrying no judgment for a one-task run; the
  asymmetry is stated in D3 rather than smoothed over. Aligning the initiative mode
  the other way — chaining it into the gate too — is left as a named boundary.
- **Auto-merge when the diff is non-protected and all checks pass** — rejected
  outright: `main` moves only by a PR a human confirmed (`CONSTITUTION.md` §1.1–1.2);
  no mode of `/t-drive` may absorb that gate, and this ADR's whole premise is that
  the trust posture does not move.
- **Run the solo task through an integration branch for uniformity with ADR-004** —
  rejected: a single task's PR already *is* the `main`-bound PR; an intermediate
  branch adds one more merge with no second reader and no batching benefit, pure
  machinery.
- **Unlimited or zero retries** — rejected for the reasons ADR-004 already gives:
  unbounded lets an unattended agent argue with a review indefinitely; zero wastes
  the common one-line-fix case. Nothing about a solo run changes that calculus.

## Consequences / revisit triggers

Accepted knowingly: a solo drive removes the human's between-stage pauses for a single
task, so a task whose declared scope *and* actual diff are both non-protected reaches
the merge gate with no independent review and no human eyes between invocation and
gate — exactly as the manual pipeline allows today, but now reachable in one
invocation. The gate itself, and the constitution's protected-surface rules, are the
unchanged backstop.

Any of these reopens this decision, as a new ADR:

1. **A defect reaches `main` via a solo drive that one of the removed between-stage
   pauses would plausibly have caught** — the solo counterpart of ADR-004's first
   trigger; the fix may be an extra mandatory stop, or making review unconditional
   after all.
2. **The inherited one-retry bound proves miscalibrated in solo runs specifically** —
   tune the count, not the shape, in a new ADR (ADR-004's second trigger, restated).
3. **Humans at the gate routinely redirect rather than confirm** — a sign the single
   stop comes too late and some judgment does exist between the stages after all.
4. **A second maintainer joins** (workflow §13 Q9) — whether a solo drive may chain
   into a gate that now requires another person's approval needs re-deciding.
5. **The initiative mode's ending is aligned to chain into `/t-ship`'s gate** — the
   boundary D3 names; doing it means a new ADR revisiting ADR-004's stop-and-name
   ending, not an edit to either ADR.
