# ADR-009: Inside a driven initiative, a sibling blocker merged into the integration branch with a ready review is satisfied

**Status:** Accepted · 2026-09-04
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–008 if a
second maintainer joins — workflow §13 Q9.)*

This ADR **amends ADR-004**, adding one clause to what Decision 1 and Decision 2 there
mean by a child's outcome being "merged", and one clause to what ADR-001 §D3.2's blocker
gate reads as "satisfied" while `/t-drive` is driving. ADR-004's own text is not edited
(append-only, `CONSTITUTION.md` §2.1); this file is the new decision, the same shape
ADR-007 and ADR-008 took. Every other decision in ADR-001, ADR-003, ADR-004, and ADR-006
stands untouched: outside a driven run, and for any blocker that is not a sibling child
of the same initiative, nothing here applies.

## Context

Issue #127. ADR-004 Decision 1 has `/t-drive` work an initiative's children in
dependency order: child A is planned, implemented, cold-reviewed, and squash-merged into
`wip/<initiative>-integration`; only then does child B, blocked by A, start. ADR-004
Decision 3 deliberately keeps A's *issue* open until the aggregate PR reaches the trunk —
that PR carries the `Closes #A` line, so a child's issue closes when its work lands where
`CONSTITUTION.md` §1.3 says outcomes are recorded, not a moment earlier.

Those two decisions were never reconciled with the gate ADR-001 §D3.2 mechanizes.
`/t-work` Phase 1 step 2 runs `.t-workflow/scripts/check-blocker-gate.sh`, which requires
every blocker to be **closed as completed**. By its letter, B is still blocked: A is
merged, reviewed, and done, but open. `/t-drive` Phase 2 step 1 said the opposite in
prose — a sibling whose outcome is "merged" no longer holds a child — with no executable
form. Three driven runs in a consumer repository (`haninaguib-devtools/locklane`,
initiatives #462, #535 and #705) each resolved the conflict the same way by hand: the
driving session read `/t-drive`'s rule as governing, cut B's branch from the integration
tip, and wrote the reading into B's record's Deviations (that repository's task records
for #464, #537 and #704), and each cold reviewer accepted it. A judgment repeated three
times identically is a rule that was never written down; a cold session that reads
`/t-work` first can refuse correctly and stall the drive.

A second, mechanical face of the same gap: `.github/workflows/ci.yml`'s blockers step
runs the same script on every PR, a driven child's PR against the integration branch
included, and fails it on the same merged-but-open sibling.

## Decision

### D1. The blocker gate takes the sibling dispositions as input, and judges the driven reading itself

`check-blocker-gate.sh` gains an opt-in input, `--siblings <initiative-id> <file>`, and
with it the rule both skills now state in the same words:

> A blocker that is a sibling child of the same initiative counts as satisfied when
> that sibling's PR is merged into the initiative's integration branch
> (`wip/<sibling>-*` onto `wip/<initiative>-integration`, state MERGED) and its latest
> cold review reads `readiness: ready`; any other blocker — outside the initiative, or a
> sibling that is open, excluded, or cancelled — is judged exactly as before: closed as
> completed, or nothing.

The file carries, per sibling blocker, the rows `forge:pr-find-by-task` and
`forge:pr-reviews` already return — PR state, head and base branch names, and each
review's body and time — so the script itself decides every branch of the rule and each
branch is a fixture in `plumbing-test.sh`: merged onto the integration branch with a
ready latest review passes; merged with a not-ready (or no) review, merged onto the
trunk instead, a PR still open, a sibling closed as not-planned, and an outside blocker
still open all fail. Only a still-**open** sibling can be satisfied this way — a
cancelled one was abandoned (ADR-001 §D3.2) whatever its PR history says. Without the
flag the script is exactly what it was.

Who supplies the file decides who may apply the reading:

- **Driven.** `/t-drive` Phase 2 step 1 builds it for every blocker that is a sibling
  merged in this run and hands it to `/t-work`, whose Phase 1 step 2 runs the gate with
  it. The one invocation that authorized the drive (ADR-004) is what authorizes
  cutting the child's branch from the integration tip and proceeding; the same
  invocation-plus-gate authority ADR-006 D6 names for the solo mode.
- **Standalone.** `/t-work <child>` with no driving session runs the plain gate first.
  On a refusal, when the issue has an `initiative`-labeled parent, it builds the same
  file for the failing sibling blockers and re-runs the gate with it — but only to
  choose its words: a pass the second time means the blocker is merged-but-open, and
  `/t-work` still stops, says exactly that, and names `/t-drive <initiative>` as the way
  to continue. It never applies the driven reading on its own: a session that is not
  driving has no integration branch to cut the child's branch from, so proceeding would
  put B's work on a branch cut from the trunk, where A's work is not.

### D2. CI does not re-judge a driven child's blockers

The same script gains `--pr-base <ref>`, and `ci.yml`'s blockers step passes the PR's
base. When the base is a driven initiative's integration branch
(`wip/<n>-integration`), the script exits 0 without judging, saying so. CI has none of
the sibling dispositions D1 needs, and rebuilding them there would be a second copy of
the same rule in a place nobody can run locally; the driving session already judged that
child, with those dispositions, before its PR existed. A PR to the trunk — every ordinary
task, and the aggregate PR itself — is gated exactly as before.

Accepted knowingly: this turns CI's blocker check off for an integration-branch PR
entirely, outside blockers included. `/t-drive` Phase 2 step 1 excludes a child on an
open outside blocker before its branch is cut, so the check would have been redundant on
the one path that produces such a PR; and no branch protection reads CI on an
integration branch, so nothing that could have blocked a merge is weakened.

### D3. What does not change

A child's issue still closes only when the aggregate PR reaches the trunk (ADR-004
Decision 3; a Non-goal of #127). A blocker outside the initiative is judged exactly as
ADR-001 §D3.2 always did. An excluded sibling is not merged, so it satisfies nothing, and
the cascade ADR-004 Decision 2 defines still runs on it. Nothing in the solo mode
(ADR-006) changes: a plain task's blockers have no siblings and no integration branch.

## Rationale

- **One rule, one executable home.** The driven reading existed in `/t-drive`'s prose
  and in three task records; `/t-work` executed a different rule. Putting the
  reconciliation in the script both skills already call — with fixtures for every
  branch — is the form `docs/workflow.md` §1 prefers: a rule the build enforces over one
  the model must remember, and the same posture `CONSTITUTION.md` §3 takes toward its own
  executable twin.
- **The authority is the review, exactly as ADR-004 Decision 1 spends it.** A `readiness:
  ready` cold review is what let A merge into the integration branch at all; treating
  that same review as what satisfies B's dependency on A adds no new trust primitive.
  Requiring the review, not the merge alone, is what keeps a merge that somehow landed
  without one from unblocking anything.
- **The integration branch is the only place B may start from.** A's work is on that
  branch and nowhere else until the aggregate PR lands. Restricting the reading to a
  driving session is not ceremony: it is the only session holding the branch B has to be
  cut from. Standalone `/t-work` naming `/t-drive <initiative>` sends the human to the
  one command that can continue correctly.
- **CI's step scoped in the script, not in YAML.** A `--pr-base` flag with a fixture is
  testable on any machine; a YAML `if:` is testable only by pushing.

## Alternatives considered

- **Close a child's issue at its integration-branch merge**, so the plain gate passes —
  rejected: ADR-004 Decision 3 keeps the issue open until the trunk because the tracker
  is the venue for process and git the record of outcome (`CONSTITUTION.md` §1.3); a
  child closed before its work reaches the trunk reads as done while an excluded
  aggregate PR could still leave it unshipped. Named a Non-goal by #127.
- **Leave the rule in `/t-drive`'s prose and have `/t-work` defer to it when driven** —
  rejected: that is the status quo that produced three hand-applied readings; a cold
  session reading `/t-work` first has no executable reason to defer.
- **A thin wrapper script `/t-drive` calls instead of a flag on the gate** — rejected:
  two scripts judging "is this blocker satisfied" is two rules to keep in sync; a flag
  on the one gate keeps the plain path byte-for-byte what it was and the driven path a
  strict, opt-in extension of it.
- **Have CI rebuild the sibling dispositions itself** (one `gh` lookup per blocker) and
  run the full D1 rule on integration-branch PRs — rejected for now: a second copy of
  the rule in YAML nobody runs locally, for a check no branch protection reads on that
  branch. Listed as the revisit below if that protection is ever added.
- **Let standalone `/t-work` apply the driven reading when it can prove the sibling is
  merged** — rejected: it would cut the child's branch from the trunk, where the
  sibling's work is not, and open a PR against the trunk that ADR-004 says must go
  through the aggregate PR instead.

## Consequences / revisit triggers

The `check-blocker-gate.sh` CLI keeps its single positional argument; the two flags are
opt-in, so `/t-work` standalone, `/t-drive` Solo mode, and every existing fixture run
unchanged. `/t-drive` Phase 2 now builds one small JSON file per child with merged
sibling blockers; `/t-work` standalone makes up to two forge reads per failing sibling
blocker, only on the refusal path.

Any of these reopens this decision, as a new ADR:

1. **Branch protection, or a required status check, is ever applied to
   `wip/<n>-integration` branches** — D2's "nothing CI could block is weakened" stops
   being true, and CI must then rebuild the sibling dispositions and run D1 itself.
2. **A driven child lands on the integration branch whose blocker's review was `ready`
   but whose work was not in fact usable by the dependent child** — ADR-004 revisit
   trigger 1, now specifically implicating the review-satisfies-dependency reading.
3. **A second maintainer joins** (ADR-001 revisit trigger 1): whether a sibling's
   dependency may be satisfied by a review alone, without a human's confirmation of
   that merge, is the same question ADR-004 revisit trigger 4 already queues.
