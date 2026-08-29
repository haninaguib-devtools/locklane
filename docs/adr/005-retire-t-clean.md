# ADR-005: Retire `/t-clean` — leave stale local worktrees and branches alone

**Status:** Accepted · 2026-08-28
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–004 if a
second maintainer joins — workflow §13 Q9.)*

This ADR supersedes only the `/t-clean` piece of ADR-002's "Replace ship/cancel teardown
with deferred, explicit cleanup" section. ADR-002's other decisions — dropping
`/t-wtree` and `/t-fix`, retiring D4's non-numeric tracker-key clause, and `/t-ship` and
`/t-cancel` no longer performing teardown or refusing to run from inside a task's
worktree — all stand exactly as ADR-002 states them. What changes here is narrower: not
whether ship/cancel clean up (they already don't, and continue not to), but what happens
to a stale local worktree or branch afterward.

## Context

ADR-002 replaced ship/cancel's automatic teardown with a new, explicit, lazy `/t-clean`
skill: nothing destroys a local worktree or branch as a side effect of shipping or
cancelling, and a human runs `/t-clean` later, on confirmation, when a stale one is
actually in the way. That ADR's own rationale was that "machinery nobody exercises is
not neutral — it is a standing cost," applied to `/t-wtree` and `/t-fix`.

The same test now falls on `/t-clean` itself. In the time since ADR-002, no operator has
ever reached for it: no stale worktree or branch has caused a collision, confusion, or
any other friction (ADR-002's own revisit trigger 2 for this decision has not fired), and
the skill exists purely as a command a cold session has to know about, with a
branch-resolution and candidate-classification algorithm of its own for
`scripts/consistency-check.sh` to keep in sync with everything else. It is the same
unused-standing-cost shape ADR-002 already retired twice over in the same document —
just discovered one layer later, in the very skill ADR-002 introduced to replace what it
removed.

## Decision

`/t-clean` is removed, with nothing replacing it. Stale local worktrees and branches left
behind by `/t-ship` or `/t-cancel` are left alone **permanently** — not lazily cleaned up
by a skill, ever. If one is genuinely in the way (a branch-name collision, disk space, or
plain tidiness), a human removes it by hand with ordinary git (`git worktree remove
<path>`, `git branch -D <branch>`) the same way they would manage any local git state
this workflow does not otherwise touch. `/t-ship`'s and `/t-cancel`'s own behavior —
no automatic teardown, runnable from any checkout, no refusal to run from inside a task's
worktree — is unaffected; this ADR removes the deferred cleanup step ADR-002 built for
after them, not the thing it was built after.

## Rationale

- **The same argument ADR-002 already made, applied one level deeper.** ADR-002 measured
  `/t-wtree` and `/t-fix` by whether an operator actually reached for them, not by
  whether they were theoretically useful; `/t-clean` fails that same test. A cleanup
  skill that nobody has run since it shipped is not "available for when it's needed" —
  it is untested machinery whose only interaction with the rest of the workflow so far
  has been costing a pipeline-table row and a `scripts/consistency-check.sh` entry.
- **Leaving a stale worktree or branch alone costs nothing until something needs that
  path back** — this is ADR-002's own consequence, accepted knowingly there, and nothing
  in the time since has shown it wrong. Removing the lazy skill does not remove the
  option to clean up by hand; it removes the pretense that a dedicated command is
  needed to do it.
- **Plain git already does this.** `git worktree remove` and `git branch -D` are
  standard commands any operator working in this repository already knows; `/t-clean`'s
  candidate-discovery and PR-state cross-referencing added convenience, not a capability
  nothing else provides, for an operation performed rarely enough that the convenience
  was never actually used.

## Alternatives considered

- **Keep `/t-clean` but stop calling it a pipeline stage** — rejected for the same
  reason ADR-002 rejected the equivalent option for `/t-wtree`/`/t-fix`: a skill that
  exists but is absent from the documented pipeline is worse than either removing it or
  keeping it, since a cold reader cannot tell dormant from deliberately hidden.
- **Fold cleanup back into `/t-ship`/`/t-cancel` as an optional final step** — rejected:
  this is exactly the "cannot delete the ground it is standing on" shape ADR-002 removed
  from those two skills; reintroducing it there would resurrect the refusal ADR-002 was
  written to eliminate.
- **Do nothing (keep `/t-clean` as-is)** — rejected: zero observed use since introduction
  and a live revisit trigger that has not fired is exactly the standing-cost profile this
  task exists to address.

## Consequences / revisit triggers

Accepted knowingly: there is no skill-enforced guarantee a stale worktree or branch is
ever cleaned up, and no tooling to discover or classify candidates — an operator who
wants a list must run the equivalent of `/t-clean`'s Phase 1 procedure by hand.

Any of these reopens this decision, as a new ADR:

1. **A stale worktree or branch actually causes a collision or confusion** — the same
   condition ADR-002 named as trigger 2 for this decision, now judged to have fired:
   re-introduce an explicit cleanup mechanism, as a skill or otherwise.
2. **A second person starts implementing** (ADR-001 revisit trigger 1, ADR-002's trigger
   1) — concurrent worktrees make a stale one more likely to collide with a new task's
   branch name, changing the cost side of this trade.
3. **Manual cleanup itself becomes a recurring source of mistakes** (e.g. an operator
   deleting a branch that still had an open PR) — the classification `/t-clean` used to
   perform (merged/closed vs. open vs. no-PR-found) turns out to carry real safety value
   after all.
