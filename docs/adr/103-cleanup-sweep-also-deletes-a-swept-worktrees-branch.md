# ADR-103: The cleanup sweep also deletes a swept worktree's local branch — widening the ADR-102 carve-out

**Status:** Accepted · 2026-08-29
**Deciders:** project owner *(solo phase; queued for review alongside ADR-001–008 if a
second maintainer joins — workflow §13 Q9.)*

## Context

ADR-102 carved a narrow, guarded exception out of ADR-005's "left alone permanently,
removed by hand only": a console-created, per-issue worktree is removed automatically
once its issue is closed, its git status is clean, and no live session is attached —
because nothing in the product ever prompts a person to notice it and clean it up by
hand, unlike a developer's own deliberately-created pipeline worktree.

`git worktree remove` only ever removes the worktree's directory. It never touches the
branch that was checked out inside it. For a per-issue worktree, that branch is
`wip/<id>-<slug>` — a name `WorktreeCreationService` mints automatically the moment a
person presses "Console" against an issue (AGENTS.md's branch convention), the same
mechanical, un-deliberate origin ADR-102 already used to justify sweeping the worktree
itself. Once ADR-102's sweep removes the worktree, that branch is exactly the kind of
artifact ADR-005 says is "left alone permanently" — but ADR-005's own reasoning was
about a developer's own branch, one a person chose to create and would notice needed
cleaning up (a name collision, `/t-clean`-era manual tidiness). A machine-minted branch
left behind by a now-deleted worktree has no such touchpoint: nobody is ever looking at
it, the same gap ADR-102 closed for the worktree it lived in. Left alone, these
accumulate in the repository's local branch list indefinitely, one per closed issue
ever opened in a console.

## Decision

Immediately after `WorktreeCleanupSweeper` successfully removes a worktree, it attempts
to delete that worktree's branch with `git branch -d` (the safe, non-forcing form) —
never `-D`. Git's own merge-into-`HEAD` check is the only judgment applied: a branch
fully merged is deleted; an unmerged branch is refused, and the sweep leaves it alone,
logging the refusal (`WorktreeCleanupSweeper`'s existing `run()` helper already logs a
`WARN` on any non-zero exit — no new retry or force path was added to route around
that). If the worktree's branch cannot be determined — a detached HEAD, or the read
fails for any reason — nothing is deleted; that is treated as "no branch to delete," not
an error.

This is a second exception to ADR-005, attached to the same guarded lineage ADR-102
started, not a new independent one: it only ever fires on a branch whose worktree the
sweep *itself just removed* under ADR-102's three-part guard. A branch whose worktree
survives — open issue, dirty, or attached — is untouched by this decision, exactly as
ADR-005 already says. A branch with no worktree at all (never swept, or removed by
hand) is likewise untouched: this decision adds no new path that goes looking for
orphaned branches on its own.

## Rationale

- **Same origin, same argument, one step further than ADR-102 already went.** ADR-102
  measured a console-created worktree's cost profile against ADR-005's own
  "disk space" language and found no human touchpoint that would ever prompt cleanup.
  The branch inside that worktree has the identical profile — automatically created,
  never deliberately chosen, no moment that surfaces it to a person — once the
  worktree it lived in is already gone. Widening the same exception to cover it is the
  same test ADR-102 applied, not a new one.
- **`git branch -d`'s refusal is the entire safety mechanism, and it is already exactly
  right.** Git itself will not delete a branch with commits unreachable from the
  current `HEAD` at the point of deletion. That is precisely the distinction this
  decision needs — shipped/merged work's branch may go, anything not yet landed must
  survive — and it requires no new logic to compute: the guard is git's, not this
  application's. `CONSTITUTION.md` §1.5 forbids weakening a guard to make work simpler;
  using `-d` and never `-D` is the guard staying exactly as strict as it already is.
- **No new judgment, no new failure mode.** The branch delete piggybacks on a removal
  the ADR-102 guard already proved safe (issue closed, worktree clean, unattached); it
  adds no new condition of its own, and a refusal here changes nothing about whether
  the worktree removal itself succeeded — the worktree is gone either way, exactly as
  before this decision existed.
- **Scoped to what the sweep itself just did, not a general branch sweep.** This
  decision does not go looking for orphaned `wip/*` branches with no worktree, or
  branches abandoned some other way (`/t-cancel`, a worktree removed by hand). Naming
  the scope narrowly here keeps ADR-005's default — branches are left alone,
  permanently, unless a specific, named exception says otherwise — intact everywhere
  else.

## Alternatives considered

- **Reconstruct the branch name from the worktree/session id's slug instead of asking
  git.** Rejected: the id's slug is fixed at worktree-creation time, but the branch
  actually checked out can drift from it — a sibling task in this same initiative
  (#340) has `/t-work` mint its own, possibly different-slugged branch inside the same
  worktree once implementation starts. Reading `git rev-parse --abbrev-ref HEAD` from
  the worktree, before it is removed, is the one source of truth for "what branch is
  actually here right now."
- **`git branch -D` (or delete-then-recreate) for a faster clean sweep** — rejected:
  forbidden outright by `CONSTITUTION.md` §1.5 and the issue's own ask; the entire
  point is that an unmerged branch's work must never be destroyed by an automated
  process nobody is watching.
- **Extend the sweep to also find and remove orphaned branches with no worktree at
  all** — rejected as broader than this task: that is a different, undirected search
  (which branches on disk have no console worktree left?) with its own risk profile,
  left to ADR-005's manual-cleanup default unless a future task names a similar
  no-human-touchpoint argument for it specifically.

## Consequences / revisit triggers

Accepted knowingly: a closed-but-dirty or closed-but-attached worktree's branch survives
indefinitely along with its worktree, following ADR-102's own accepted consequence; and
a detached-HEAD per-issue worktree (once #340 lands) sweeps its worktree with no branch
to clean up at all, which is correct, not a gap.

Any of these reopens this decision, as a new ADR:

1. **`git branch -d` ever deletes a branch that turns out to matter** — this should be
   structurally impossible (git's own merge check is the only gate), but if it happens,
   the single most serious trigger: suspend the branch-delete step and re-derive the
   guard rather than patching around it, the same posture ADR-102 took for its own
   trigger 1.
2. **Unmerged branches pile up faster than anyone expected**, suggesting the
   fully-merged case is rarer in practice than assumed — worth re-measuring, not
   necessarily worth changing the guard.
3. **A worktree-creation path this decision was not written for appears** — e.g. a
   project-console worktree (ADR-102's own trigger 3, #339) gaining a branch of its
   own under some future change — and needs its own branch-deletion rule, distinct
   from the per-issue one this ADR covers.
