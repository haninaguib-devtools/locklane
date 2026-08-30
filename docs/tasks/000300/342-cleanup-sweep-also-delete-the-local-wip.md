# 342 — Cleanup sweep: also delete the local wip/ branch when removing a closed issue's worktree
Issue: #342 · Part of: #337

## Asked
When `WorktreeCleanupSweeper` removes a closed issue's worktree (ADR-008), the local
`wip/<id>-<slug>` branch survives — `git worktree remove` never touches branches, and
ADR-005 says branches are left alone. ADR-005's reasoning was about a developer's own
deliberately created branch; these are machine-created by a console button,
accumulating with nobody ever prompted to clean them — the same distinction ADR-008
used to justify sweeping the worktrees themselves. Extend the sweep: after successfully
removing a worktree, delete its local branch with `git branch -d` (lowercase, the safe
form) — git itself refuses if the branch is unmerged, so shipped work's branch goes and
anything unmerged survives, with no new judgment logic. This widens the ADR-008
carve-out from worktrees to their branches and needed its own ADR.

## Done when
- After the sweep removes a worktree whose branch is fully merged into `main`, the
  local `wip/<id>-*` branch is gone (real-git test).
- An unmerged branch survives sweep removal of its worktree, and the refusal is
  logged, not retried or forced.
- The new ADR is in `docs/adr/`, referencing ADR-005/ADR-008, with `CONSTITUTION.md`
  §4's one-line rule updated.

## Explicitly not
- Remote branch deletion — governed by the forge's delete-on-merge setting and
  `/t-cancel`, not the sweep.
- Deleting any branch whose worktree the sweep did not itself just remove.

## Decisions made along the way
- The issue body and sibling issue #339 both cite "ADR-006" for the worktree-cleanup
  sweep decision; the actual ADR is **ADR-008**
  (`docs/adr/008-automatic-worktree-cleanup-sweep.md`) — ADR-006 is
  `006-single-task-driving.md`, unrelated. Task #319's own record shows the sweep ADR
  was originally slotted as "006" and renumbered to 008 after ADR-006 was claimed
  elsewhere in the interim; the stale citation was never fixed in #319/#339's text.
  This task's new ADR and `CONSTITUTION.md` update cite ADR-008 correctly (`/t-plan`
  342, confirmed against the actual files in `docs/adr/`).
- New ADR is `docs/adr/009-*.md` (next free number after 008) (`/t-plan` 342).
- Branch name is read from the worktree with `git rev-parse --abbrev-ref HEAD` before
  `git worktree remove` runs (not reconstructed from the worktree id's slug, which can
  drift from the actual current branch once `/t-work` renames it) — a detached HEAD
  (or a failed read) means "no branch to delete", not an error.

## Deviations / notes
- none
