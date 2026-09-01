# ADR-106: /l-release — a single command from release version to dispatched release

**Status:** Accepted · 2026-09-01
**Deciders:** project owner *(solo phase)*

This ADR does not touch [ADR-004](004-autonomous-initiative-driving.md) or
[ADR-006](006-single-task-driving.md); it adds a second, narrower, explicitly-invoked
exception to [ADR-001](001-phase0-delivery-workflow.md) D1's "nothing auto-chains",
sitting beside `/t-drive`'s rather than inside it. `/l-release` is locklane-local — the
`l-` prefix, outside the template's `t-*` namespace, is what lets it live in the new
consumer-skill slot `AGENTS.md` reserves after the `t-*` pipeline table
(`docs/architecture/local-slots.md`) without a template sync ever touching it.

## Context

Issue #477. Cutting a release (`docs/architecture/releasing.md`) already has an
established shape: a notes task lands `CHANGELOG.md`'s new section on `main` through an
ordinary pipeline PR, `release.yml` is dispatched by hand, and a follow-up task bumps
`<revision>` to the next snapshot. Every step already exists and is already correct;
what is missing is a way to run the whole sequence without a human re-typing four or
five commands and re-deriving the same gates (does `<revision>` match, does the tag
already exist) each time.

`/t-drive` is not itself the answer, because a release is not one task — it is a task,
then a side-effecting dispatch that only makes sense after that task's PR is actually on
`main`, then a second task. Bolting dispatch-and-verify onto `/t-drive` would make a
generic driving skill know about release semantics it has no other reason to carry.
`/l-release` is instead a thin, release-specific script over the existing stages: it
never re-implements `/t-open`, `/t-drive`, or `/t-ship`'s own contracts, and it never
performs a write those skills would not otherwise perform for their own invocation.

## Decision

### D1. `/l-release <version> [<next-version>]` is a parameterized composition, not a new stage

It gates, then calls `/t-open` once and `/t-drive` twice (once per task), in this order:

1. **Gate, before any write.** `<revision>` in `pom.xml` must equal
   `<version>-SNAPSHOT`, and tag `v<version>` must not already exist
   (`git tag`/`gh release view`). Either check failing stops the run loudly, before
   `/t-open` is ever called, naming exactly what to fix — no partial state, nothing
   created.
2. **The notes task.** `/t-open` creates a fully shaped task: generate the `v<version>`
   section of `CHANGELOG.md` via `scripts/generate-release-notes.sh generate`, per
   `docs/architecture/releasing.md` § Cutting a version, with "dispatch `release.yml`
   after this merges" named in the issue as a post-merge human step (so the issue reads
   correctly even if someone later runs it standalone). `/l-release` then invokes
   `/t-drive <id>` (solo mode, ADR-006) on it, which chains plan-if-needed, work, and
   review-if-needed into `/t-ship`'s merge-confirmation gate — the pipeline's ordinary
   mechanisms, unmodified.
3. **First human stop, worded to cover the dispatch too.** `/t-ship`'s gate
   (`docs/architecture/confirmation-gates.md`) is reached exactly as it always is; only
   its evidence and question grow one line each, naming that confirming also authorizes
   dispatching `release.yml` once the merge lands — the same "fold the enabling
   side-action into the one gate that authorizes it" shape `/t-ship` Procedure step 5
   already uses for a branch-protection flip. **On `abort`, `/l-release` stops
   entirely**: no dispatch, and the bump task in step 5 is never opened. Nothing after
   this point runs without having passed through this one stop.
4. **Dispatch and verify, after the merge, before anything else.** `gh workflow run
   release.yml`, watched (`gh run watch` or equivalent polling) to conclusion, then
   confirms release `v<version>` exists with a body equal to the notes section just
   merged. A failed or red run stops `/l-release` and reports it — the skill never
   silently proceeds to the bump task on a dispatch it cannot confirm succeeded.
5. **The bump task.** `/t-open` creates a task setting `<revision>` to the next
   snapshot — `<next-version>-SNAPSHOT` when given, else the default patch increase
   (`0.1.0` → `0.1.1-SNAPSHOT`) — then `/l-release` invokes `/t-drive` (solo mode) on
   it, chaining to its own `/t-ship` gate.
6. **Second human stop**, ordinary and unremarked: this is the bump task's own
   merge-confirmation gate, ADR-006 D3's chain-into-the-gate behavior, ended with
   nothing further to authorize.

`/l-release` performs no tracker write, no merge, and no dispatch itself outside what
steps 2–6 name — every write is a call into `/t-open`, `/t-drive`, or the explicit
post-first-gate dispatch step, never a shortcut around any of them.

### D2. Exactly two human stops, both are pipeline stops that already exist

The two confirmations named in D1 (step 3, step 6) are the *same* gate `/t-ship` always
presents, reached through `/t-drive`'s existing chain — `/l-release` adds no third gate
and no new gate mechanism. Bundling the dispatch's authorization into the first gate
(rather than adding a separate "dispatch now?" question) keeps the count at two:
`docs/architecture/confirmation-gates.md`'s "exactly one gate per turn" is still true at
each individual stop; what changes is that one stop's evidence names two acts instead
of one, exactly as `/t-ship` Procedure step 3/5 already does for its own branch-
protection flip.

### D3. The exception's bound: composition only

`/l-release` is authorized to call `/t-open` and `/t-drive` because the human's single
`/l-release <version>` invocation is the ask that covers every write those calls make
for their own stage — the same shape `AGENTS.md` §Conventions already states for
`/t-drive` itself ("the human names `/t-drive <id>` once, and that single ask covers
every stage it chains internally"). This ADR grants `/l-release` **no new stage semantics**
beyond that composition:

- It never opens, comments on, labels, or closes an issue except through `/t-open`'s or
  `/t-drive`'s own chained stages performing their own contract.
- It never merges a PR, marks one ready, or flips branch protection except through
  `/t-ship`'s own Procedure, reached via `/t-drive`.
- It never auto-confirms either gate on the human's behalf — an `abort` at either stop
  ends the run exactly where the human stopped it, with everything before that point
  already landed (the notes PR merged; nothing after undone) and everything after it
  never attempted.
- The version choice is never automated past a suggestion: the patch-default bump is
  proposed, and the human confirms or overrides it at the bump task's own gate — this
  ADR does not change `docs/architecture/releasing.md`'s existing "a human decides when
  a version is cut" non-goal.

## Rationale

- **A release is two tasks and a side effect, not one task** — `/t-drive` alone cannot
  express "run the dispatch after this specific PR merges", and teaching it to would
  leak release-specific knowledge into a generic driving skill every other task also
  uses. A thin composing skill keeps that knowledge local to the one place that needs
  it.
- **Reusing `/t-ship`'s own precedent for folding in an enabling action** means the
  dispatch's authorization needs no new confirmation vocabulary: a human who already
  knows how `/t-ship`'s branch-protection flip works recognizes this gate's shape
  immediately.
- **Naming this a second exception, not a widening of `/t-drive`'s**, keeps the
  boundary legible: `/t-drive`'s own exception is bounded to what its `SKILL.md` chains
  (plan/work/review/ship across one task or one initiative's children); this exception
  is bounded to what `/l-release`'s `SKILL.md` chains (exactly two drives and one
  dispatch), and the two documents never need to agree on a shared boundary because
  each states its own.

## Alternatives considered

- **Extend `/t-drive` with a "release mode".** Rejected: `/t-drive` is deliberately
  generic — ADR-004/006 never mention releases — and a release-specific mode would be
  the one place that genericness breaks, for a benefit (`/t-drive` learns about
  `release.yml`) nothing else needs.
- **A single `/t-drive`-driven initiative covering both tasks, with the dispatch as a
  "child".** Rejected: an initiative's children are all plan/work/review/merge units
  (ADR-004); a `gh workflow run` dispatch is not a child in that sense, and forcing it
  into that shape (a no-op task whose "work" is running a script) is machinery for its
  own sake.
- **Skip the second human stop — auto-merge the bump PR since it is mechanical.**
  Rejected outright: `CONSTITUTION.md` §1.1 and ADR-006's own D3/D6 already establish
  that a solo drive's one stop is `/t-ship`'s gate, never removable; a "mechanical"
  change is exactly the kind of judgment call `CONSTITUTION.md` §1.2 still asks a human
  to make once, at the gate.
- **Have `/l-release` write the dispatch and verification into the bump task instead of
  running them itself between the two tasks.** Rejected: the dispatch must happen after
  the *notes* task merges and before the *bump* task is even opened (the bump task's
  own existence has no bearing on whether the release published) — sequencing it inside
  the wrong task would either dispatch too early (notes not yet merged) or make the bump
  task's scope include an unrelated side effect.

## Consequences / revisit triggers

- `/l-release`'s `SKILL.md` is the only place that knows the release-specific sequence;
  `/t-open`, `/t-drive`, and `/t-ship` remain exactly as ADR-001/004/006 left them.
- A release still costs exactly two human confirmations — the same count the manual
  sequence already required (notes-PR merge, bump-PR merge) — with the dispatch moved
  from a separate manual action into the first confirmation's authorized scope, at zero
  net new gates.
- **Revisit if** a dispatch failure after the first gate turns out to leave the run in a
  state `/l-release` cannot cleanly report (e.g. the merge landed but the watch cannot
  determine the run's outcome) — the fix is better failure reporting in the skill, not a
  new gate.
- **Revisit if** a second maintainer joins (`workflow §13` Q9): whether `/l-release` may
  still chain two solo drives into gates that now require another person's approval
  needs re-deciding, exactly as ADR-006 revisit trigger 4 already flags for `/t-drive`
  itself.
