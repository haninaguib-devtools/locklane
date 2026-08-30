# 338 — Project consoles: create worktrees detached, without a console/ branch
Issue: #338 · Part of: #337

## Asked
A project-level console session gets its own worktree on a freshly minted
`console/<suffix>` branch (`ProjectConsoleService.start` → `WorktreeCreationService`).
These consoles exist for pre-issue discussion and `/t-open`; they almost never commit,
so every one opened leaves a branch behind permanently. Create the worktree with a
detached HEAD at `origin/main` instead (`git worktree add --detach <path> origin/main`)
— no branch at all. The worktree still provides full file isolation between sessions;
if a session legitimately transitions to task work, `/t-work` creates the proper
`wip/<id>-<slug>` branch in that worktree, which is the branch actually wanted.

## Done when
- Opening a project console creates a sibling worktree whose HEAD is detached at
  current `origin/main`; `git branch --list 'console/*'` gains no new entry.
- `/t-work` run inside such a worktree can still create and check out its `wip/`
  branch (covered by a test exercising real git, per the existing `GitTestRepos`
  pattern).
- Existing engine/client tests pass.

## Explicitly not
- Removing the worktree when the console closes — its own task in this initiative.
- Any change to issue-level consoles — its own task in this initiative.
- Cleaning up `console/*` branches already on disk — removed by hand if ever in the
  way (ADR-005).

## Decisions made along the way
- none

## Deviations / notes
- none
