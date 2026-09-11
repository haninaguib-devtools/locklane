# 896 — Resolve task records from the fetched trunk, not the stale holder checkout
Issue: #896

## Asked

`IssueDetailService.recordPath()` finds an issue's task record by scanning `docs/tasks` in the project's holder checkout (`project.workareaPath()`) on disk. That checkout is a detached HEAD the engine pins at clone time and later re-detaches in `WorktreeCreationService` when it needs the trunk branch free; the sweeper and worktree creation both `git fetch --prune origin`, but nothing ever moves the checked-out tree forward. On this machine the Locklane holder sits at the v0.2.7 release from 2026-09-03 while `origin/main` is 104 commits ahead, so every record landed since (bucketed or flat, #892's own included) resolves to nothing and the overview tab shows "no record yet". #892 fixed the layout half (flat `docs/tasks/<id>-<slug>.md` first, buckets second); this task fixes the staleness half. Resolve the record against the fetched trunk instead of the working tree: list `docs/tasks` (top level, then each bucket subdirectory) from `origin/<default branch>` with `git ls-tree` in the holder repo, keeping the same flat-first, bucket-second precedence and the same relative-path result shape, so the lookup follows the fetch that already happens and no longer depends on which commit the holder happens to have checked out. Fall back to the current on-disk scan only when the remote-tracking ref does not exist (a project that has never fetched).

## Done when

- `IssueDetailServiceTest` has a case where the holder checkout is at an older commit whose tree lacks the record while `origin/<default>` carries it at `docs/tasks/<id>-<slug>.md`, and `detail(id).recordPath()` returns that path.
- The existing flat-layout and bucket-layout tests still pass, exercised through the trunk ref rather than the working tree.
- A test where `origin/<default>` is absent still resolves a record present on disk (fallback).
- With the fix deployed, opening issue #892 in the Locklane project shows `docs/tasks/892-resolve-flat-layout-task-records-in-issu.md` while the holder checkout is left at its stale commit.
- `./mvnw -B -pl engine -am -Dskip.npm test` passes.

## Explicitly not

- Advancing or resetting the holder checkout itself; the checkout stays wherever `WorktreeCreationService` leaves it.
- Any client/UI change; `IssueDetail.recordPath` keeps its shape and the overview tab keeps building the record link from it.
- Reading records from GitHub over the API instead of the local fetch.

## Decisions made along the way
- `IssueDetailService` gained a fourth constructor parameter, `defaultBranch` (the
  project's recorded default branch, nullable), resolved to `origin/<branch>` the same
  way `WorktreeCreationService.trunkRef` does. `ProjectGhResources.build()` and
  `buildWithoutCheckout()` now pass `project.defaultBranch()` through.
- `DEFAULT_TRUNK = "main"` is duplicated locally in `IssueDetailService` rather than
  reused from `WorktreeCreationService` — that constant is package-private in
  `persistence`, and `IssueDetailService` lives in `github`; no shared git-runner
  utility exists in this codebase (every class shelling out to `git` has its own small
  private `run(...)` returning `ProcessOutcome`, per `WorktreeCreationService`'s own
  convention), so a new one wasn't introduced for a single caller.
- `recordPathFromTrunk` uses `git ls-tree <ref>:<path>` (rev:path syntax) rather than
  `git ls-tree <ref> -- <path>` so the returned names are already relative to that
  directory — matching the on-disk `DirectoryStream` scan's shape with no extra
  stripping.

## Deviations / notes
- Done-when's "opening issue #892 in the Locklane project shows
  docs/tasks/892-....md while the holder checkout is left at its stale commit" is
  demonstrated by the new `IssueDetailServiceTest` cases (a throwaway repo whose
  checked-out branch is reset behind `origin/main`, which still carries the record) —
  this session has no access to the actual running engine instance or its database to
  drive the real Locklane project end-to-end, so that exact manual step is unverified
  live; the automated test exercises the identical mechanism.

## Agents
- work: claude-code / claude-sonnet-5
