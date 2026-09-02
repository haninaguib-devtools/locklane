# 554 — Allow project-console worktree removal when its branch already landed on main
Issue: #554

## Asked
When a project-console worktree's checked-out branch has already been squash-merged
into `main` (so the branch's own commit is no longer a literal ancestor of
`origin/main`, because squash-merge rewrites the SHA), the worktree is currently
refused removal forever from the project page — the same refusal a worktree carrying
real, un-landed work gets. Teach the removal guard to tell the two cases apart: a
checked-out branch whose content has already landed on `origin/main` becomes
removable like any other stale scratch worktree; a branch carrying real, un-landed
work keeps being refused, unchanged.

## Done when
- The guard distinguishes a checked-out branch whose work has already landed on
  `origin/main` (even under a rewritten SHA, e.g. via squash-merge) from a branch
  carrying real, un-landed work, and refuses removal only for the latter.
- A worktree whose checked-out branch is clean and already-landed is removable from
  the project page.
- A worktree whose checked-out branch is clean but genuinely unmerged (no equivalent
  content reachable from `origin/main`) is still refused, unchanged from today.
- A worktree with uncommitted changes is still refused, unchanged from today.
- `CONSTITUTION.md` §4 point 4 is updated to state the new rule, backed by a new ADR
  in `docs/adr/` recording the rationale and superseding ADR-104's exact-ancestor
  requirement.
- Test coverage in `WorktreeCleanupSweeperTest` covers: an already-landed branch is
  allowed, a genuinely unmerged branch is still refused, and uncommitted changes are
  still refused.

## Explicitly not
This task only changes the project-console worktree guard (ADR-104). The per-issue
worktree cleanup guard (ADR-102/103), which already has its own distinct
ancestor/clean/no-session conditions, is untouched.

## Decisions made along the way
- Squash-merge/rewritten-SHA detection: cheap literal-ancestor check first
  (`git merge-base --is-ancestor <branchTip> origin/main`); when that fails, compute
  the whole diff the branch introduces since its merge-base with `origin/main`
  (`git diff <base> <branchTip>`) and compare its `git patch-id --stable` against the
  patch-id of every commit reachable only through `origin/main` since that same base —
  a match means the branch's content already landed under a different SHA. Any
  failure along the way (fetch, merge-base, patch-id) resolves to "not landed" — the
  same safe direction the existing `isAncestorOfOriginMain` already takes.
- New ADR numbered 107 (next free number after ADR-106), superseding only ADR-104's
  branch-checked-out condition; its other three conditions are restated unchanged.

## Deviations / notes
- Two test files outside the plan's Allowed paths needed a one-line fixture fix each:
  `ProjectConsoleServiceTest.closingASessionWithABranchCheckedOutKeepsItsWorktree` and
  `ProjectWorktreesServiceTest.removeRefusesAProjectConsoleWorktreeWithABranchCheckedOut`
  both exercised the guard through a different call site using a bare `checkout -b`
  with zero commits — under the new content check that branch is trivially identical
  to `origin/main` (a literal ancestor) and therefore correctly counts as landed, so
  the full suite failed after the guard change until each fixture added one real
  commit not reachable from `origin/main`, matching what `WorktreeCleanupSweeperTest`'s
  own updated fixtures already do. Test-only, same behavior contract, no new scope.
- `isBranchLanded`'s "no diff between merge-base and branch tip" case (e.g. an empty
  commit) resolves to **not landed**, not the "trivially safe" `true` an earlier draft
  used — an empty commit still exists nowhere but this branch, and CONSTITUTION.md
  §1.5's safe-direction rule means "cannot prove landed" must stay "not landed."
