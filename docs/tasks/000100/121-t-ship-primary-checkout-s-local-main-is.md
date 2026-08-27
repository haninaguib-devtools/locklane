# 121 — t-ship: primary checkout's local main is not fast-forwarded when the shipped task used a worktree
Issue: #121 · Part of: #123

## Asked
`/t-ship`'s cleanup step (Procedure step 5) branches on whether the shipped task has a
worktree, and the two branches are mutually exclusive: when the task lived in its own
worktree, the skill only removes that worktree — it never runs the "fast-forward this
checkout" logic, even though the invoking session may be sitting on `main` in the
primary checkout the whole time. Shipping #92 (PR #119, worked in `../locklane-92`)
left the primary checkout on `main` one commit behind `origin/main` with nothing
surfacing the gap. Restructure the step so worktree removal and updating the invoking
checkout are independent actions that both always run.

## Done when
- `/t-ship`'s cleanup fast-forwards whatever branch the invoking checkout is actually
  on, independent of whether the shipped task's own branch had a worktree — the
  worktree-removal branch and the "update this checkout" branch are no longer mutually
  exclusive.
- If the invoking checkout is on the shipped task's own branch (no separate worktree),
  behavior is unchanged from today.
- If the invoking checkout is on `main` (or anything else) while the shipped task lived
  in its own worktree, that checkout still ends up fast-forwarded to `origin/main` (or
  is left alone with a stated reason, if it's on something the skill has no business
  moving).
- `./scripts/consistency-check.sh` passes.

## Explicitly not
- No behavior change to `/t-fix` or `/t-cancel` teardown.
- Not the stranded-remote-branch defect — that was #120, already shipped; same skill,
  different fix.

## Decisions made along the way
- The plan (hani, 2026-08-27) flags one deliberate behavior extension as a natural
  consequence of un-conflating the two concerns: after a *successful* worktree removal,
  the guarded local-branch delete now also runs — previously the worktree path never
  deleted the stale local `wip/` branch at all. The existing
  `git rev-parse --verify … && git branch -D …` guard makes it a no-op when the branch
  is already gone, and the "skip when the worktree was left standing (removal refused on
  uncommitted changes)" rule is kept.

## Deviations / notes
- none
