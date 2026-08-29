# ADR-002: Trim the Phase 0 workflow

**Status:** Accepted · 2026-08-28
**Deciders:** project owner *(solo phase; same standing note as ADR-001 — the
heightened approval bar for protected surfaces is itself still open, workflow §13 Q9, so
this ADR is queued for review the same way if a second maintainer joins.)*

## Context

ADR-001 was accepted four days ago, on 2026-08-24, as the baseline: two invariants
(issue-first, human-confirmed-PR-only `main`), and every other stage — plan, worktree,
review — chosen per task, with heightened ceremony always required on a protected
surface. The first week of actually running it, on the delivery system's own repository,
surfaced machinery that a single operator never reaches for:

- **The worktree stage (`/t-wtree`, ADR-001 D1)** exists for two cases — two tasks in
  flight at once, or a long-running one — that a solo operator hits rarely enough that
  the skill's presence is pure standing cost: another command to know about, another
  branch-resolution algorithm to keep in sync with `/t-work`'s
  (`scripts/consistency-check.sh` check 8 exists only because there are two copies to
  drift).
- **The no-issue fix path (`/t-fix`, ADR-001 D2)** was priced to keep typo fixes cheap
  without breaking the PR-only invariant. In practice it is a second code path through
  the same two skills (`/t-cancel` and `/t-ship` both special-case it — ADR-001 D3.4,
  `/t-cancel` Phase 1 step 2) for a volume of changes low enough that routing them
  through an ordinary issue costs almost nothing extra, while the eligibility gate
  (no meaning change, protected-surface refusal, 2-file/~10-line caps) is one more
  thing every skill and the anti-creep retro (ADR-001 D2) have to keep honest.
- **D4's non-numeric tracker-key handling** (`PROJ-142` → `proj-142`, lowercased into
  branch names and record paths) is speculative generality: the active backend
  (`docs/adapters/TRACKER.md`) is GitHub Issues, whose ids are already numeric, and nothing
  in this repository or its near-term plan exercises a Jira-shaped key. A companion task
  (#26, blocked on this ADR) is already dropping the untested Jira/GitLab adapter rows for
  the same reason; keeping D4's non-numeric-key clause after that lands would document
  behavior nothing implements.
- **Ship/cancel teardown** (ADR-001 D3) currently deletes the task's worktree and local
  branch as part of both `/t-ship` step 5 and `/t-cancel` step 4, and — because a skill
  cannot delete the ground it is standing on — both skills first refuse to run from
  inside the worktree they are about to remove ("Where this runs" in both `SKILL.md`
  files), sending the human back to the primary checkout. That refusal is friction with
  no safety payoff once the worktree stage itself is optional and rarely used: it exists
  only to protect a teardown step that a solo operator, on a repository with no
  concurrent collaborators to confuse, does not need performed automatically at every
  ship or cancel. GitHub's own `delete_branch_on_merge` repository setting already
  deletes the remote branch on merge without any skill code; what is left after that is
  purely local housekeeping (a stale worktree directory, a local branch ref), which
  costs nothing left undone until the next time it is in the way.

None of this touches the two invariants themselves, the confirmation gates, or
squash-only merges — ADR-001's rationale for those stands unchanged. This ADR is a
tightening (less required machinery, not more), which is why it is still recorded as a
full ADR: it supersedes ratified decisions, and `CONSTITUTION.md` §2.1 requires that to
happen only by a new, append-only decision naming what it replaces.

## Decision

### Drop the worktree stage from per-task stages (supersedes ADR-001 §D1)

`/t-wtree` is removed. ADR-001 D1's stage list — plan, worktree, review chosen per task —
loses the worktree entry; the two invariants (issue-first, human-confirmed-PR-only
`main`) and the protected-surface plan/review requirement are otherwise unchanged. A task
that genuinely wants an isolated checkout still gets one — `git worktree add` by hand, or
one an engine creates for a launched session — the workflow simply stops naming and
maintaining a skill for it. `/t-work` keeps its own branch-resolution algorithm; there is
no longer a second copy to keep in sync.

### Remove the no-issue fix path entirely (supersedes ADR-001 §D2)

`/t-fix` is removed. Every change, however small, starts from a tracker issue —
`/t-open` (or, for a true one-line typo, an ordinary small issue and task, not a
carve-out). D2's eligibility gate (no meaning change, protected-surface refusal, file/line
caps, anti-creep counting) is removed along with it: there is no longer a second path for
it to gate. `/t-cancel` D3.4 ("a `/t-fix` change has nothing to cancel") is superseded as
moot by the same stroke — see below.

### Replace ship/cancel teardown with deferred, explicit cleanup (supersedes ADR-001 §D3, in part)

D3's teardown clause — `/t-cancel` (and, by the same logic, `/t-ship`) removing a clean
worktree and deleting the branch as part of the stage itself — is superseded. The new
model:

- **`/t-ship` merges and stops.** It marks the PR ready, gets the human's confirmed
  merge, squash-merges, and closes the issue. It no longer removes the task's worktree or
  local branch, and — because it no longer needs to delete the ground it might be
  standing on — it no longer refuses to run from inside a linked worktree either; it is
  runnable from any checkout that can push the confirmed merge.
- **Remote branch deletion rides the forge's own setting.** GitHub's
  `delete_branch_on_merge` repository option (already asserted by
  `scripts/github-bootstrap.sh`) deletes the merged branch on the remote without any
  skill-side step; `docs/adapters/FORGE.md`'s `forge:pr-merge` row stops asserting a
  `--delete-branch` flag as skill-owned behavior.
- **Local worktree and branch cleanup becomes an explicit, lazy step — a new
  `/t-clean`.** Nothing destroys a local worktree or branch as a side effect of shipping
  or cancelling; a human (or a future scheduled pass) runs `/t-clean` when a stale
  worktree or branch is actually in the way. This is genuinely lazy: leaving a shipped
  or cancelled task's local worktree in place costs nothing until something needs that
  path back.
- **`/t-cancel`'s remaining teardown** (closing the PR unmerged, per D3 as written)
  is unaffected by this section — closing a PR is not a worktree/branch deletion, and
  `forge:pr-close`'s own branch-deletion behavior is covered by the same
  `delete_branch_on_merge`-equivalent reasoning as `/t-ship`'s. What changes for
  `/t-cancel` is exactly the same as for `/t-ship`: it stops removing the local worktree
  and local branch itself, and stops refusing to run from inside one.
- **D3.4 is moot, not superseded on its own terms.** D3.4 ("a `/t-fix` change has
  nothing to cancel") described a special case of a path that no longer exists once D2
  is gone; there is nothing left for it to except from.
- **D3's other safety rules are untouched.** D3.1 (the escape hatch — durable reasoning
  is promoted to an ADR, not buried in a close comment), D3.2 (cancelling never satisfies
  a dependency), D3.3 (cancelling never cascades silently), and D3.5 (cancellation is
  terminal, not deferral) all continue to bind exactly as ADR-001 states them. Only the
  mechanical teardown clause and D3.4 are affected.

### Drop the non-numeric tracker-key clause (supersedes ADR-001 §D4)

D4's bucket-path rule stands (`docs/tasks/<bucket>/<id>-<slug>.md`, bucket = id rounded
down to the nearest 100, zero-padded to 6 digits) but its non-numeric-key clause — "a
non-numeric tracker key computes the bucket from its numeric part and keeps the full key,
lowercased, in the filename" — is dropped. Task ids are GitHub issue numbers: numeric,
full stop. The lowercasing step referenced throughout the skills (`ADR-001 §D4`, e.g. in
`/t-work` Phase 1 step 4, `/t-cancel` Phase 1 step 3) becomes a no-op for a numeric id and
is retired along with the clause that motivated it, in the sibling tasks that touch those
skill files (#26).

## Rationale

- **Machinery nobody exercises is not neutral — it is a standing cost.** Every skill that
  special-cases `/t-fix` or `/t-wtree`, every place D4's non-numeric branch executes for
  an id that is always numeric, is a place `scripts/consistency-check.sh` has to verify
  stays in sync (check 8's `/t-wtree`/`/t-work` branch-algorithm duplication exists for
  exactly this reason) and a place a cold reader has to understand before trusting the
  rest of the document. Removing what is not used shrinks that surface without touching
  either invariant.
- **The worktree and no-issue-fix stages were justified by solo-operator convenience;
  dropping them is the same argument taken one step further.** ADR-001 already priced
  ceremony per task on the premise that a process too expensive to follow gets routed
  around. A stage that exists but is not reached for is neither followed nor routed
  around — it is just unused weight, and ADR-001 revisit trigger 1 ("a second person
  starts implementing") is exactly the condition under which worktrees and independent
  paths start earning their keep again; nothing here forecloses re-adding them then.
- **Deferred cleanup is honest about what actually needs to happen synchronously at ship
  or cancel time.** The two invariants — issue-first, human-confirmed-PR-only `main` —
  are what keep `main` safe to leave alone; a stale local worktree directory is not a
  safety property, it is tidiness, and tidiness that blocks the operator from shipping
  from wherever they happen to be sitting is a worse trade than tidiness deferred to a
  moment that actually calls for it.
- **`delete_branch_on_merge` is not a downgrade.** It is the same remote-branch-deletion
  outcome `forge:pr-merge`'s explicit flag produced, moved to configuration the forge
  already enforces on every merge (including ones this workflow's skills did not
  perform), which is one fewer place for the skill-side command to drift from what
  actually happens.

## Alternatives considered

- **Keep `/t-wtree` and `/t-fix` but stop documenting them as a "stage"** — rejected: a
  skill that exists but is not in the pipeline table is worse than either removing it or
  keeping it, since a cold reader has no way to know whether it is dormant or
  deliberately hidden.
- **Keep the ship/cancel teardown but make it best-effort (skip on refusal instead of
  stopping)** — rejected: today's refusal-and-redirect is already the safe behavior for
  the case that matters (uncommitted changes); making the destructive step best-effort
  doesn't reduce its cost, it just makes the outcome less predictable. Removing the
  automatic step entirely is the actual cost reduction.
- **Fold `/t-clean` into `/t-ship`/`/t-cancel` as a final optional sub-step instead of a
  separate skill** — rejected: it would resurrect the exact "cannot delete the ground it
  is standing on" refusal this ADR is removing, since the ship/cancel skill would still
  need to run from outside the worktree to offer the cleanup.
- **Drop D4's non-numeric clause now but leave the untested Jira/GitLab adapter rows in
  place** — rejected as inconsistent: the adapter rows exist to serve exactly the
  non-numeric-key case D4 describes (Jira's `PROJ-142`); dropping one without the other
  leaves documented machinery with nothing behind it. Both drop together, via this ADR
  and its sibling task #26.

## Consequences / revisit triggers

Accepted knowingly: a task worked in its own worktree now leaves that worktree behind
after `/t-ship`/`/t-cancel` until someone runs `/t-clean`; there is no skill-enforced
guarantee that a stale worktree is ever cleaned, only that it is harmless to leave. A
future Jira/GitLab adopter re-derives the non-numeric-key handling this ADR retires,
rather than finding it ready-made.

Any of these reopens this decision, as a new ADR:

1. **A second person starts implementing** (ADR-001 revisit trigger 1, restated here
   because it bears directly on D1 and D2 as retired by this ADR) — worktrees prevent
   two people from colliding in one checkout, and a no-issue fast path may be worth its
   ceremony cost again once more than one person's typo fixes are in flight.
2. **A stale worktree or branch actually causes a collision or confusion** — e.g. a
   forgotten worktree holding a branch a new task's name would otherwise reuse — showing
   that lazy cleanup was priced too cheaply.
3. **A repository under this workflow cannot use `delete_branch_on_merge`** (a forge or
   plan tier that does not support it) — the remote-branch-deletion step returns to being
   skill-owned for that backend, and `docs/adapters/FORGE.md` gains a per-backend note
   rather than assuming the setting everywhere.
4. **A Jira, GitLab, or other non-numeric-key backend is actually adopted** — D4's
   non-numeric handling is re-derived (or re-added by un-superseding this clause in a new
   ADR) rather than assumed still latent in this one.
