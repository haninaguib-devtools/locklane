# ADR-108: The project-console cleanup guard judges "landed" against the project's recorded trunk on origin, not a hardcoded `origin/main`

**Status:** Accepted · 2026-09-02
**Deciders:** project owner *(solo phase)*

This ADR amends [ADR-104](104-remove-a-project-console-worktree-on-tab-close.md) and
[ADR-107](107-project-console-worktree-removal-tolerates-a-squash-merged-branch.md):
every place either one names `origin/main` as the ref a project-console worktree is
judged "landed" against now reads "the project's default branch on origin" —
`origin/main` when the project recorded none, exactly as before, and the project's own
recorded branch (e.g. `origin/master`) otherwise. Nothing else about either decision
changes: the same four conditions (session ended, clean, detached-HEAD ancestry or
checked-out-branch landedness), the same content-equivalence fallback for a
squash/rebase merge, the same safe-direction failure handling.

## Context

Issue #583, split from #582. #582 fixed worktree *creation*: opening a console or
issue worktree on a project whose default branch is not `main` (for example `master`,
which plain `git init` produces on a host with no `init.defaultBranch` configured — the
observed case) used to fail outright with `fatal: invalid reference: origin/main`,
because `WorktreeCreationService` built every `git worktree add` from a literal
`origin/main`. #582's fix — `WorktreeCreationService.trunkRef(ProjectRecord)`,
resolving `origin/<defaultBranch>` (falling back to `origin/main` when the record
carries none) — left the *cleanup* guard untouched on purpose (see #582's own
Non-goals): `WorktreeCleanupSweeper.isAncestorOfOriginMain` and `isBranchLanded`
(added by ADR-104 and narrowed by ADR-107) still compared against a literal
`origin/main`, and `ProjectConsoleService.close`'s guard wiring delegates to the same
two checks.

That leftover is not a crash — `git merge-base --is-ancestor <head> origin/main`
against a repo with no `origin/main` ref simply fails, and both checks already treat
any failure along the way as "not landed," the same safe direction ADR-104 and
ADR-107 both established. So the practical effect on a `master`-trunk project is not
an error surfaced anywhere, but silent permanent leakage: every console-created
worktree on that project is judged "not landed" forever, regardless of its actual
state, and neither the periodic sweep nor tab-close ever removes one. Safe, but it
defeats the entire purpose of ADR-102/ADR-104's carve-out — a worktree nobody has a
reason to know exists accumulates on disk without bound.

## Decision

`WorktreeCleanupSweeper`'s project-console guard (`removalRefusalReasonForProjectConsole`
and the two checks it calls) resolves the ref it compares a worktree's HEAD or
checked-out branch against from the project's own record — reusing
`WorktreeCreationService.trunkRef(ProjectRecord)`, the same resolution #582 already
established for worktree creation, applied here for the first time to worktree
*removal*: `origin/<defaultBranch>` when the project recorded one,
`origin/main` otherwise. Both refusal-reason strings the guard returns name
this resolved ref rather than a hardcoded `origin/main`, so a `master`-trunk project's
refusal reads correctly instead of naming a ref that was never being checked.

Every other condition either ADR names is unchanged:

- ADR-104's session-ended, clean, and detached-HEAD-ancestor-of-trunk conditions;
- ADR-107's checked-out-branch content-equivalence fallback (`git patch-id --stable`)
  for a squash-merged or rebase-merged branch, now computed against the resolved
  trunk ref instead of `origin/main`;
- the safe-direction handling both ADRs already specify: any failure along the way
  (fetch, merge-base, patch-id) still resolves to "not landed" or "not an ancestor,"
  never the reverse.

`ProjectConsoleService.close` needs no logic change: it already delegates to
`removalRefusalReasonForProjectConsole` rather than re-deriving the guard, so the
resolved-trunk behavior reaches tab-close automatically, the same way ADR-104's
original guard did.

## Rationale

- **Reuses #582's own resolution rather than inventing a second one.** Creation and
  cleanup now agree on exactly one function (`WorktreeCreationService.trunkRef`) for
  "which ref is this project's trunk on origin" — a worktree is always judged against
  the same ref it was created from, which is the whole point of the guard (was this
  commit ever going to be reachable from where the project actually lands work).
- **The failure mode this closes is silent, not loud — worth naming explicitly.** Every
  affected check already fails safe (`CONSTITUTION.md` §1.5): nothing here was ever at
  risk of removing a worktree it shouldn't. The bug was pure permanent leakage on any
  non-`main`-trunk project, invisible until someone went looking at how much disk a
  project's worktrees were consuming — exactly the observability gap ADR-104's own
  Context section already worried about for the ordinary "nobody is prompted to clean
  this up" case, now doubled by a guard that can never fire at all.
- **No new condition, no loosened one.** This amendment touches only which ref two
  existing string constants resolve to; it adds no new way for a worktree to qualify
  for removal and removes none of the existing safeguards guarding that path.

## Alternatives considered

- **Detect the default branch fresh at guard time (e.g. `git symbolic-ref
  refs/remotes/origin/HEAD`) instead of trusting the project's own record** —
  rejected: #582's own Non-goals already scoped "how the default branch is detected or
  stored" out, and duplicating that detection here would let creation and cleanup
  silently disagree about a project's trunk if the two ever computed it differently.
  Reusing the one recorded value (and the one resolution function) keeps them provably
  in sync.
- **Leave the cleanup guard as `origin/main` and treat the leak as acceptable given it
  fails safe** — rejected: "fails safe" here means "never cleans up," which is the
  worktree-accumulation problem ADR-102/ADR-104 exist to solve in the first place, on
  every project whose trunk was ever anything but `main`.

## Consequences / revisit triggers

Accepted knowingly: a project whose default branch was recorded incorrectly (or
changed on the remote after the project was added) inherits that same error into
cleanup judgments — this ADR does not add any independent correctness check on the
recorded value beyond what #582 already established for creation.

Any of these reopens this decision, as a new ADR:

1. **This guard ever allows removal of a worktree that turns out to have held
   unrecoverable work** — the same single most serious trigger ADR-104 and ADR-107
   both name; suspend the resolved-trunk comparison and re-derive it rather than
   patching around a near-miss.
2. **A project's recorded default branch and its actual trunk on origin diverge** (the
   remote's default branch changed after the project was added, say) — this decision
   assumes the recorded value stays accurate; if that assumption breaks in practice,
   revisit how (or how often) the record is refreshed, not just how cleanup reads it.
