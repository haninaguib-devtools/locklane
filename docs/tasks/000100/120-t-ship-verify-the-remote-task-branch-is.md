# 120 — t-ship: verify the remote task branch is deleted after merge
Issue: #120 · Part of: #123

## Asked
`/t-ship` merges with the forge's branch-deleting merge (`gh pr merge --squash
--delete-branch` on GitHub). gh deletes the local branch first and the remote branch
second — and when the local deletion fails, it exits without ever attempting the remote
one. The local deletion fails in a completely ordinary pipeline state: the task branch is
checked out in the task's own worktree (`/t-wtree` exists precisely to set that up). The
merge itself succeeds, so the ship reports success while a stale `wip/<id>-*` branch
survives on `origin` with nothing surfacing the miss — the same silent-failure shape as
#39, reached through a different door. This actually happened shipping #100 (PR #116).
The skill's cleanup step already handles the worktree and the local branch explicitly,
but never re-checks the remote ref. Teach it to verify the remote branch after the merge
and delete a survivor explicitly.

## Done when
- `/t-ship`'s cleanup step verifies the remote branch after the merge — e.g.
  `git ls-remote --heads origin <headRefName>` returns nothing — and deletes a survivor
  explicitly (`git push origin --delete <headRefName>`), using the `headRefName` kept
  from PR resolution, never a re-derived slug.
- `grep -q 'ls-remote' .claude/skills/t-ship/SKILL.md` exits 0.
- `docs/adapters/FORGE.md`'s `forge:pr-merge` row documents the abort ordering: a failed
  local deletion means the remote deletion never ran.
- `./scripts/consistency-check.sh` passes.

## Explicitly not
- No behavior change to `/t-fix` or `/t-cancel` teardown, even though they share the
  fetch-then-clean pattern — if the same verification belongs there, that is its own
  decision.

## Decisions made along the way
- The remote verification lives in the skill's step 4 (the fetch-then-clean boundary),
  not step 5: step 5 branches on whether a worktree exists, while the remote ref must be
  verified in both cases — placing it beside `git fetch --prune` keeps it unconditional
  and leaves step 5's two-branch structure untouched (agent, 2026-08-27).

## Deviations / notes
- none
