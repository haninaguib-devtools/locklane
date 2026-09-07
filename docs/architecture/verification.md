# Human verification

**Status:** binding convention. Implements ADR-010 §D4/§D6's concrete mechanism
(issue #144): human verification is evidence a person actually judged, recorded as a
real workflow period distinct from `/t-review`'s independent review, that a task can
wait on — across sessions or days — before it is eligible for `/t-ship`.

## Why this exists, in plain terms

A cold review from `/t-review` tells you the diff is sound. It cannot tell you a
contributor actually clicked through the preview and liked what they saw, or that a
domain expert checked the numbers against the real world. That second kind of check
sometimes has to wait for a person who is not the implementer or the reviewer, and it
can take days. This document names that wait, so it is a state the workflow can see and
gate on, not a private conversation nobody wrote down.

## Vocabulary

A **verification entry** names one thing a role must check. A task may have any number
of them, none, or — unchanged from every task before this existed — only the ordinary
free-form `human_checks:` a plan already supports (§Relationship to `human_checks`,
below).

Each entry carries:

| Field | Meaning |
|---|---|
| `role` | Who or which role must verify — a contributor, a maintainer, a domain expert, or a named person. Generic on purpose: this document never assumes Proposarium or any other external system (ADR-010). |
| `what` | What must be checked or exercised, in plain language. |
| `required` | `true` when `/t-ship` must not merge until this entry is resolved; `false` when it is informational, carried the same way an ordinary `human_checks` item is. |
| `evidence` | What evidence is expected — a preview URL exercised plus a written note, a screenshot, an explicit sign-off comment. Never a preview's own reported status standing in for this by itself (ADR-010 §D4: a passing preview proves the software runs, never that a human judged it). |
| `scope` (optional) | Globs naming the paths a change to could affect this entry. Omitted means the whole task can affect it — the safe default (§Invalidation, below). |

## States

Every entry is in exactly one of four states — Done-when's own words, made exact:

- **`pending`** — declared, not yet checked. The entry's starting state.
- **`verified`** — the named role checked it and it passed. Recorded with the evidence
  supplied and the revision (commit sha) it was checked against.
- **`rejected`** — the named role checked it and it did not pass. The task returns to
  implementation; a rejected entry never ships as-is.
- **`risk-accepted`** — a human maintainer explicitly decided to proceed without the
  check passing, accepting the residual risk. This is a **resolution**, not a
  **pass**: it satisfies `/t-ship`'s gate the same way `verified` does, but it is
  never worded, printed, or read as "verified" anywhere — not in the record, not in
  `check-verification-gate.sh`'s output, not in `/t-ship`'s confirmation-gate evidence
  line (`docs/architecture/confirmation-gates.md`). Recording *that a human looked and
  chose to accept the risk* is the whole of what distinguishes this from a silent skip
  (ADR-010 §D4).

`pending` and `rejected` block `/t-ship` when `required: true`. `verified` and
`risk-accepted` resolve the gate — unless stale (below).

## Recording an outcome

Recording a verification outcome is an ordinary record edit, made by `/t-work` (Phase
2's existing "update the record as you go" rule already covers this — it is not a new
invocation path or a new fix-mode trigger): when a human supplies an entry's outcome —
what the role checked, what evidence they gave, and at what revision — `/t-work` writes
`state`, `evidence`, `revision`, `by`, and `date` into that entry in the task record and
commits it alongside whatever else that pass does. A `risk-accepted` entry additionally
records `risk:` — the residual risk being accepted, and why, in the human's own words
where possible, so the record shows a judgment was made rather than a checkbox ticked.

## Invalidation: when a new commit undoes a verification

Done-when requires that "a new commit invalidates verification when the changed work
could affect it" — not that literally every commit does. The mechanism:

A `verified` or `risk-accepted` entry is **stale** once the branch's head commit
differs from the entry's recorded tested revision, unless the entry declared `scope:`
and the diff between that revision and the new head touches none of those globs (the
same glob-match style `.t-workflow/scripts/protected-paths.sh` uses for its own
patterns). An entry with no `scope:` is stale by default whenever the revision differs
— the safe default, consistent with ADR-010 §D7's fail-toward-absent posture: an
ambiguous "could this have affected it?" defaults to yes, never to a silent pass.

`/t-work` is the one skill that actually creates the new commit, so it is the first
place staleness is judged and acted on: before Phase 3 pushes, it checks every
`verified`/`risk-accepted` entry in the record against the diff it is about to push,
and — when an entry is now stale — flips its `state` back to `pending`, clears
`evidence`/`revision`, and appends a Deviations note naming which entry, why, and at
which commit. An entry `/t-work` judges clearly unaffected (e.g. a docs-only or
unrelated-path change against an entry whose `scope:` excludes it) is left untouched,
and that judgment is itself worth a one-line Deviations note so a reader is not left
wondering why it wasn't reset.

`/t-ship`'s own gate (below) re-derives `stale` independently rather than trusting the
record's `state` field blindly — the same defense-in-depth `check-review-gate.sh`
already applies to a review's own `isolation:` line by checking it against the head
commit's real timestamp instead of taking the line's word for it.

## The shipping gate

`/t-ship` blocks on any `required: true` entry that is `pending`, `rejected`, or stale
(`.claude/skills/t-ship/SKILL.md`'s own precondition; mechanized by
`.t-workflow/scripts/check-verification-gate.sh`). A `risk-accepted` entry — like a
`verified` one — resolves the block, but is still surfaced by name in the
confirmation-gate evidence line: accepting a risk is visible to the human confirming
the merge, never silently folded into "no pending checks."

## Relationship to `human_checks`

`human_checks:` (a plan's existing free-form list of judgments no command settles) is
untouched by this document and needs no migration. `verification:` is a new, optional,
more structured entry type for a judgment that specifically requires a named role to
exercise real evidence, possibly asynchronously, possibly over several sessions —
`human_checks` remains the right shape for an ordinary one-shot human judgment made at
review or ship time. A plan or record that declares no `verification:` entries — every
task before this one, and most tasks after it — behaves exactly as it always has:
`check-verification-gate.sh` reads zero entries as nothing to check (§States), and
`/t-ship` finds no new precondition to satisfy.

## Non-goals (unchanged from issue #144)

This document never requires external verification for every task, never lets an
external service merge, never replaces `/t-review`'s independent review, and never
defines how a project deploys the preview a role might exercise (`docs/adapters/` and a
future preview-adapter contract, sibling #146, own that).
