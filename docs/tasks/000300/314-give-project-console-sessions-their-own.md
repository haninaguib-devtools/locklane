# 314 — Give project console sessions their own worktree
Issue: #314

## Asked
On the project console page, pressing "+" starts a new console session, but every
session it opens runs in the project's single shared main checkout instead of its own
git worktree — unlike the per-issue console flow, which already gives each session its
own worktree. Change the project console's "+" button so each new session gets its own
freshly created git worktree (a sibling checkout, following the existing
`../<repo-name>-<slug>`-style pattern used for issue worktrees) instead of sharing the
main checkout. Closing a session does not need to remove that worktree — cleaning up
worktrees on close is separate follow-on work, out of scope here.

## Done when
- Pressing "+" on the project console page creates a new git worktree checkout (not the
  shared project workarea) and the console's PTY session starts inside it.
- Pressing "+" twice produces two separate worktrees — no reuse between sessions.
- Closing a project console session does not remove the worktree from disk — it is left
  in place.
- The existing issue-console worktree flow (`WorktreeCreationService`,
  `WorktreeController`) is unchanged and its tests still pass.
- `./mvnw -B test` passes, including new tests covering create-worktree-on-open for the
  project console.
- The client test suite passes, including a test confirming the "+" button no longer
  targets the shared main checkout.

## Explicitly not
- Changing the issue-console page's worktree-vs-main picker or its per-issue worktree
  reuse behavior.
- Deciding whether the git branch created for a project-console worktree is deleted or
  kept — moot for now since worktrees aren't removed by this task.
- Removing a project-console worktree from disk, on close or otherwise — deferred to a
  future task (not yet filed).
- Deleting the worktree on an implicit disconnect (browser closed, network drop) — same
  follow-on scope as the point above.

## Decisions made along the way
- `WorktreeCreationService.createWorktree`/`repoName` widened from `private` to
  package-visible `static` and reused directly by `ProjectConsoleService`, rather than
  extracting a new shared class — the methods already touched no instance state, so
  this is the smaller diff and matches the existing precedent of
  `WorktreeCreationService.slug` already being used as a static helper outside the
  class (haninaguib, 2026-08-29).
- The worktree's branch is named `console/<8-hex-suffix>` (no project-id prefix, no
  issue) — each project console session has no issue to derive a `wip/<id>-<slug>`
  branch from, and each project is its own repo, so the suffix alone is enough to keep
  branches from colliding (haninaguib, 2026-08-29).
- Extracted the throwaway-local-git-repo test setup (`initTestRepo`/`currentBranch`)
  out of `WorktreeCreationServiceTest` into a new shared `GitTestRepos` test helper,
  since `ProjectConsoleServiceTest`/`ProjectConsoleControllerTest` now need the exact
  same real-git setup to exercise the new worktree-creating path (haninaguib,
  2026-08-29).

## Deviations / notes
- The issue's client-facing done-when item ("a test confirming the '+' button no longer
  targets the shared main checkout") doesn't correspond to any client-observable
  behavior change: `workingDirectory` is opaque to `ProjectConsoleComponent`, forwarded
  straight from the engine's response into `<app-terminal [dir]>` regardless of its
  value. Addressed it as a client test asserting that two consoles opened back-to-back
  each get the specific `dir` the engine reported for them (not a value hardcoded or
  collapsed to one shared checkout) — see
  `project-console.component.spec.ts`'s "gives each console its own working directory
  from the engine" test — since that's the only thing the client can actually assert
  about this behavior.
