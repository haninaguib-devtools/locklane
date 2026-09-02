# ADR-107: A project-console worktree's checked-out branch no longer refuses removal once its work has landed on `origin/main`

**Status:** Accepted · 2026-09-01
**Deciders:** project owner *(solo phase)*

This ADR amends [ADR-104](104-remove-a-project-console-worktree-on-tab-close.md): it
narrows ADR-104's second condition (a checked-out branch is left alone
unconditionally) to a checked-out branch whose work is genuinely un-landed, and
reopens the specific alternative ADR-104's own "Alternatives considered" rejected —
see Alternatives considered below for why the rejection no longer holds. ADR-104's
other three conditions (session ended, clean, detached-HEAD ancestor-of-`origin/main`)
are unchanged, and ADR-102/ADR-103's per-issue guard is untouched.

## Context

Issue #554. ADR-104's second condition refuses removal of any project-console
worktree with a branch checked out, full stop: "a checked-out branch means the
console outgrew scratch and is left alone." That condition's own "Alternatives
considered" entry weighed removing such a worktree once its branch was merged or
deleted, and rejected it as "broader than this task and outside what a project
console is for."

Observed case: worktree `locklane-console-0d254aee` had branch
`wip/529-bump-revision-to-0-1-9-snapshot-for-next` checked out, a clean working tree,
its single commit `5013e56` already squash-merged into `main` as `5d14994` (PR #530),
and its own remote branch already deleted — a worktree whose work is safely landed by
every measure that matters, yet the project page refuses its removal permanently,
identically to a worktree that still carries real unmerged work. This is precisely
the gap ADR-104's rejected alternative anticipated, now observed in practice rather
than hypothesized at design time: the branch-checked-out condition, as written, treats
"grew a real branch" and "still carries real, un-landed work" as the same fact, when
they are not — a branch can grow, do its job, and land, and a worktree left checked
out on it afterward is disposable scratch again, no different from one that never
grew a branch at all.

`removalRefusalReasonForProjectConsole`'s existing detached-HEAD condition already
solves an adjacent version of this: a detached worktree's HEAD counts as landed once
`git merge-base --is-ancestor` says so. That literal-ancestor test is exactly what
squash-merge (and rebase-merge) breaks — the resulting commit on `main` carries the
same content but a different SHA, so a checked-out branch's tip is never a literal
ancestor of `origin/main` even when its work is fully landed. Telling "landed under a
rewritten SHA" from "genuinely un-landed" needs a content check, not a SHA check.

## Decision

`removalRefusalReasonForProjectConsole`'s branch-checked-out condition (ADR-104 point
2) is narrowed: a checked-out branch refuses removal only when its work has **not**
landed on `origin/main`. "Landed" means either of:

1. the branch tip is a literal ancestor of (freshly fetched) `origin/main` — the
   ordinary fast-forward or merge-commit case, the same test the detached-HEAD
   condition already uses; or
2. the whole diff the branch introduces since its merge-base with `origin/main` is
   content-equivalent (by `git patch-id --stable`) to some commit reachable only
   through `origin/main` since that same merge-base — the squash-merge or
   rebase-merge case, where the SHA changes but the content does not.

Any failure along the way — the fetch, the merge-base, or a patch-id computation —
resolves to **not landed**, the same safe direction the existing detached-HEAD check
already takes: an ambiguous read only ever keeps a worktree around longer, never
removes one it shouldn't (`CONSTITUTION.md` §1.5).

ADR-104's other three conditions are unchanged: session-ended, clean, and (for a
detached worktree) ancestor-of-`origin/main`. This decision touches only what counts
as a disqualifying checked-out branch; it does not touch what happens to that branch
once the worktree holding it is removed — no branch-delete step is added here (see
Alternatives considered), so a landed branch simply survives, ungoverned, exactly as
ADR-005's ordinary "left alone permanently" default already treats any branch outside
ADR-103's own narrower per-issue carve-out.

## Rationale

- **The rejected alternative's premise no longer holds once "merged" and "landed
  under a rewritten SHA" are told apart.** ADR-104 rejected "remove once the branch is
  merged or deleted" by reasoning that a console with a real branch has, by that fact
  alone, outgrown scratch use. That reasoning is still right for a branch carrying
  real, un-landed work — this decision does not touch that case. It is wrong for a
  branch whose entire reason to exist has already been fulfilled: nothing about
  finished work being reachable only under a different SHA makes a clean, empty
  worktree less disposable than one that was never checked out on a branch at all.
- **Reusing the existing patch-id-equivalence shape rather than inventing a second
  one.** The detached-HEAD condition already draws exactly this distinction (literal
  ancestor via SHA) for the one case squash-merge cannot break (a worktree that never
  committed anything of its own, or whose HEAD was never rewritten elsewhere). Adding
  the content-equivalence fallback extends the same "landed vs. not" question to the
  case a rewritten SHA hides, rather than introducing an unrelated second mechanism.
- **No new consequence for git's own safety.** `git worktree remove` (no `--force`)
  still refuses on any uncommitted or untracked state regardless of this guard, and
  the clean-worktree condition is unchanged — this decision only ever widens which
  *checked-out-branch* worktrees the guard itself is willing to consider, never
  weakens the cleanliness or session checks around it.

## Alternatives considered

- **Also delete the now-superfluous branch once its worktree is removed, mirroring
  ADR-103 for the per-issue path** — rejected for this task: the issue that prompted
  this decision (#554) asks only that the worktree stop being refused, and ADR-103's
  own branch-delete step exists for a worktree-creation path (`wip/<id>-<slug>` from
  `/t-work`) where the branch's fate is already this project's concern in a way a
  project console's incidentally-checked-out branch is not. Extending branch cleanup
  to this second path is a decision on its own, not a free add-on to this one — it
  stays open as ADR-104's own revisit trigger 2 (a project-console worktree needing
  its branch preserved or reaped past removal) rather than folded in here.
- **Treat "merged" as "the branch's upstream tracking ref is gone" instead of a
  content check** — rejected: a deleted remote-tracking branch is suggestive but not
  proof — a branch can lose its upstream for reasons unrelated to being merged (a
  force-push, a renamed remote, a fetch that pruned it for other reasons), and trusting
  it would violate `CONSTITUTION.md` §1.5's "never weaken a guardrail" the moment that
  assumption is wrong. The content check is mechanical and needs nothing about the
  branch's remote state.
- **Only check literal ancestry and accept that squash-merged branches stay stuck**
  — rejected: this is exactly today's behavior, and exactly the friction issue #554
  reports — it does not solve the problem, it restates it.

## Consequences / revisit triggers

Accepted knowingly: a project-console worktree whose branch has landed keeps that
branch, ungoverned by any automatic cleanup — the same as any other branch under
ADR-005's default — until a human removes it by hand or a future ADR extends
ADR-103's branch-delete shape to this path (see the first alternative above).

Any of these reopens this decision, as a new ADR:

1. **This guard ever allows removal of a worktree that turns out to have held
   unrecoverable work** — the same single most serious trigger ADR-104 and ADR-102
   both name for their own guards; suspend the branch-checked-out landed-check and
   re-derive it rather than patching around a near-miss.
2. **The patch-id-equivalence check proves too expensive or too slow** against a
   project whose `origin/main` has accumulated many commits since a long-lived
   branch's merge-base — bound the search window or replace the mechanism, but never
   by weakening what counts as "landed."
3. **A project-console worktree's checked-out-and-landed branch ever needs deleting
   automatically** — promote the first alternative above from "considered and
   deferred" to decided.
