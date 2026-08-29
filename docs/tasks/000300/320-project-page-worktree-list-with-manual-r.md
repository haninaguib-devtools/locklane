# 320 — Project page: worktree list with manual remove and cleanup-sweep trigger
Issue: #320 · Part of: #317

## Asked
Add a view on the project page listing every worktree tied to that project's issues —
issue number, filesystem path, clean/dirty status, and whether a console session is
currently attached — so a human can directly verify the console button behavior and
cleanup sweep from the other two child tasks (#318, #319) are working as expected. Each
row gets a manual "remove worktree" action using the same safety guard as the periodic
sweep (issue closed, clean, no attached session — refuse with a clear message
otherwise). The page also gets a single "run cleanup now" button that triggers the
sweep on demand instead of waiting for its schedule.

## Done when
- The project page shows a worktree list covering every worktree for that project, with
  issue, path, dirty/clean status, and session-attached status per row.
- Each row's "remove" action deletes the worktree only when it is clean and has no
  attached session, matching the sweep's guard, and refuses with a clear message
  otherwise.
- A page-level "run cleanup now" button invokes the same sweep logic on demand and the
  list reflects the result afterward.

## Explicitly not
- None beyond what's stated above.

## Decisions made along the way
- Blocker gate: `tracker:list-blockers 320` still shows #319 as `OPEN` on the tracker
  (a child issue only closes once the initiative's aggregate PR reaches `main`,
  AGENTS.md), not `CLOSED`. Per ADR-004 and `/t-drive`'s own Phase 2 step 1 ("blocked by
  another child of this initiative, not yet resolved → hold; revisit once that child's
  outcome (merged or excluded) is known"), the gate this task actually depends on is
  #319 having *merged into the integration branch* `wip/317-integration`, which it has
  (`fdf018f`) — confirmed before starting work, so #319's actual guard logic
  (`WorktreeCleanupSweeper`) is present and reusable in this checkout (`/t-drive` 317,
  2026-08-29).
- Extended `WorktreeCleanupSweeper` (#319) rather than writing a second guard: made
  `removalRefusalReason(ConsoleWorktree)` public (a granular version of the existing
  private `isSafeToRemove`, returning a human-readable reason for whichever check
  failed first), and made `isClean(Path)` and `removeWorktree(ConsoleWorktree)` public.
  `sweep()` itself is unchanged in behavior — it now calls `removalRefusalReason(...).isEmpty()`
  instead of a separately-maintained `isSafeToRemove`, so the periodic sweep and the
  manual remove action are provably the same guard, not two copies of it (haninaguib,
  2026-08-29).
- New `ProjectWorktreesService`/`ProjectWorktreesController` at
  `/api/projects/{projectId}/worktrees` (list, `DELETE /{worktreeId}`,
  `POST /cleanup`) rather than folding this into the existing per-issue
  `WorktreeController`/`IssueWorktreeService`: this view is project-wide and has no
  per-user ownership filter (matching `IssueWorktreeService#allIssueWorktrees()` and
  the sweep itself — a worktree left behind by any user is still something a project
  overseer needs to see and can remove), unlike `WorktreeController`'s per-issue,
  per-owner listing (haninaguib, 2026-08-29).
- "Run cleanup now" calls `WorktreeCleanupSweeper#sweep()` unmodified — the exact
  system-wide sweep the schedule runs, not a project-scoped variant. Scoping it to one
  project would mean either a second sweep method (a second copy of the guard) or
  filtering `sweep()`'s own loop from the outside, which it exposes no way to do.
  Trade-off accepted: a project's "run cleanup now" button can, as a side effect, also
  remove another project's already-eligible worktree — no less safe, since the guard is
  identical, just wider in scope than the button's own page. Documented on
  `ProjectWorktreesService`'s class doc so this isn't rediscovered as a surprise later
  (haninaguib, 2026-08-29).
- The worktree list only renders once the project is `READY` (same gate the existing
  console button already uses on this page) — a cloning or failed project has no
  worktrees to show (haninaguib, 2026-08-29).

## Deviations / notes
- none
