# 585 — Discover per-issue console worktrees git-natively in the cleanup sweep, not from the session record tab-close deletes

Issue: #585

## Asked
Once an issue console is closed, its worktree is never cleaned up and never shown on
the project page, even after its issue is closed and the tree is clean — the exact
state the automatic cleanup sweep exists to handle. The cause: closing an issue console
deletes the very `worktree_sessions` row `IssueWorktreeService.allIssueWorktrees()`
relies on to find the worktree, so it becomes invisible to both the periodic sweep and
the project page's worktree list. Make per-issue discovery git-native the same way
project-console worktrees already are (`git worktree list --porcelain`, cross-referenced
against the sibling-directory naming convention `<repoName>-<issueNumber>`), never from
a persisted session record. The three-part removal guard (issue cached as `CLOSED`,
`git status --porcelain` empty, no live session attached) and the ADR-103 `git branch
-d` step stay exactly as they are — only how a worktree is found changes.

## Done when
- `WorktreeCleanupSweeper.sweep()` evaluates every per-issue worktree git reports as
  registered to a project's repository whose directory name matches
  `<repoName>-<issueNumber>`, whether or not a `worktree_sessions` row exists for it. A
  same-named directory that git does not list as a linked worktree is ignored (same
  rule as `allProjectConsoleWorktrees()`).
- A unit test in `WorktreeCleanupSweeperTest` (or the nearest existing sweeper test)
  creates a real per-issue worktree, deletes its session record the way
  `SessionRegistry.close` does, stubs the issue as `CLOSED`, runs `sweep()`, and asserts
  the worktree directory is gone and the returned removed-id list names it. A sibling
  test asserts a record-less worktree whose issue is still `OPEN`, or whose tree is
  dirty, is left in place.
- `GET /api/projects/{projectId}/worktrees` lists a record-less per-issue worktree with
  its issue number, clean flag, and `sessionAttached: false`, and `DELETE
  /api/projects/{projectId}/worktrees/{worktreeId}` removes it under the same guard —
  the worktree id for a record-less worktree is derived deterministically (e.g.
  `<projectId>-<issueNumber>-…`) so the existing remove endpoint's lookup still resolves
  it. The manual "Run cleanup now" trigger, which calls the same `sweep()`, removes it
  too.
- `sessionRegistry.close(worktreeId)` after removal remains a documented no-op for an id
  with nothing live or recorded, so the removal path does not need a record to exist.
- `./mvnw -B test` passes; `./.t-workflow/scripts/consistency-check.sh` passes.
- Human check: after closing an issue console on a closed, merged issue, the next sweep
  (or "Run cleanup now") removes the worktree directory from disk and `git worktree
  list` in the project checkout no longer shows it.

## Explicitly not
- Changing any of the three guard conditions, the no-`--force` removal, or the ADR-103
  branch delete — this task changes discovery only.
- Cleaning up the orphaned `console_resume_sessions` row left behind for a swept
  worktree; it is harmless to the sweep and out of scope here.
- Project-console worktree discovery (`allProjectConsoleWorktrees()`), which already
  works this way and is the model for this change.
- Any client-side change to the project page's worktree list; the endpoint's row shape
  is unchanged.

## Decisions made along the way
- Per-issue discovery moved into `WorktreeCleanupSweeper` (a new `allIssueWorktrees()`,
  mirroring the existing `allProjectConsoleWorktrees()`), rather than making
  `IssueWorktreeService.allIssueWorktrees()` itself git-native. `WorktreeCleanupSweeper`
  already owns `ProjectRepository` and the `git worktree list --porcelain` machinery
  the project-console family uses; reusing that avoided adding a new `ProjectRepository`
  dependency to `IssueWorktreeService`, which ~13 unrelated test files construct
  directly. `IssueWorktreeService.allIssueWorktrees()` and its `ConsoleWorktree` record
  are removed outright — the class no longer has any reason to expose this listing.
- A discovered worktree's id is synthesized as `<projectId>-<issueNumber>-worktree`
  (never read from a persisted session's own id, which discovery no longer consults at
  all) — matches the Done-when's own suggested shape.

## Deviations / notes
- Removing the now-unused `IssueWorktreeService` constructor parameter from
  `WorktreeCleanupSweeper` required mechanical (argument-list-only) fixes to four test
  files outside this task's declared scope, which construct a `WorktreeCleanupSweeper`
  as a dependency for unrelated tests: `ShellSessionServiceTest`,
  `ProjectConsoleServiceTest`, `ProjectConsoleControllerTest`, and
  `ProjectWorktreesControllerTest` (the last also needed its `createWorktree` helper's
  returned id updated to the new synthetic discovery id, the same fix applied to
  `ProjectWorktreesServiceTest`). No test logic or assertions changed in the first
  three beyond the constructor call; `ShellSessionServiceTest` additionally dropped one
  assertion (`allIssueWorktrees()` excludes a shell session) that tested a mechanism
  removed in this task — shells stay out of the git-native listing trivially, since
  they mint no worktree of their own.
