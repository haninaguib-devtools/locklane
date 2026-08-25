# 21 — Expose issue hierarchy (initiatives and their sub-tasks) as a tree endpoint
Issue: #21 · Part of: #1

## Asked
Add a backend endpoint that derives the initiative/task hierarchy from GitHub issue
data — parsing `Part of: #<n>` markers from issue bodies and the `initiative` label —
and returns a tree: each initiative with its direct sub-tasks nested beneath it,
standalone tasks alongside. Needed so the sidenav (a separate, blocked client task)
can nest initiatives' sub-tasks visually.

## Done when
- A new endpoint (`GET /api/issues/tree`) returns the hierarchy, derived from the
  already-cached issue data (#4's `GhIssueCache`) — no additional live `gh` calls
  beyond what's already cached.
- Nesting is one level deep only (an initiative's direct children).
- An issue whose `Part of:` target does not actually carry the `initiative` label is
  treated as standalone, not nested.

## Explicitly not
- Any client-side rendering — split to #22.
- Status/blocked-state derivation beyond what #16 already exposes per-issue — this
  task only supplies the parent/child shape.

## Decisions made along the way
- `IssueTreeService` reads `GhIssueCache.issues()` directly (already populated by
  #4's scheduled refresh) — no new fetch/cache path added, matching the issue's own
  "no additional live gh calls" constraint (haninaguib, 2026-08-25).
- Response shape: a flat `List<TreeNode>` of top-level nodes (initiatives with
  `children`, or standalone tasks with an empty `children` list) — `TreeNode(number,
  title, kind, state, children)`, `kind` is `"INITIATIVE"` or `"TASK"`. Kept simpler
  than the old app's `TreeResponse`/`Node` shape (no `hint`/`blockedBy` fields) since
  this task's Non-goals explicitly exclude status/blocked-state derivation
  (haninaguib, 2026-08-25).
- Route: `GET /api/issues/tree`, added to the existing `IssueController` (not a new
  controller) since it's core issue-listing data, same as `list()`/`detail()`.
  Verified — with a dedicated `@WebMvcTest` routing test, not just an assumption —
  that Spring correctly resolves the literal `/tree` segment ahead of the
  `/{number}` path variable on the same controller, so `GET /api/issues/tree` never
  gets swallowed by the numeric-issue lookup and fails `int` conversion
  (haninaguib, 2026-08-25).
- Edge cases from the issue's own done-when tested explicitly: a task pointing at an
  issue number that exists but isn't `initiative`-labeled is standalone, not dropped;
  a task pointing at a nonexistent issue number is standalone; an initiative with no
  children still appears with an empty `children` list rather than being omitted
  (haninaguib, 2026-08-25).
- Manually verified against real data: the running app's `/api/issues/tree` nests
  every open task correctly under initiative #1 (the only `initiative`-labeled issue
  in this repo today), including closed ones (still present, `state: "CLOSED"`) —
  matching real GitHub state, not just the fake-backed unit tests. First manual run
  actually hit a *stale* engine process left running from an earlier, unrelated
  session (old compiled classes, no `/tree` route, hence a 400) — killed it,
  reran against a genuinely fresh process, and confirmed correct behavior
  (haninaguib, 2026-08-25).

## Deviations / notes
- none
