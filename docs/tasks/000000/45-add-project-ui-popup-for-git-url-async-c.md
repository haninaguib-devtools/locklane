# 45 — Add Project UI: popup for git URL, async clone status, retry/delete
Issue: #45 · Part of: #41

## Asked
Add an "Add Project" button that opens a popup asking for the project's git repo URL
and name. The name field is optional and prefills automatically once the user enters
a URL (derived from the repo, e.g. its name), but the user can overwrite it before
submitting. Submitting calls the create-project API and shows the project's async
clone status (cloning/ready/failed) in the sidenav until it resolves. A failed clone
can be retried or the project removed from the same UI.

## Done when
- An "Add Project" button opens a popup collecting a git repo URL and a name field.
- Entering a URL prefills the name field with one derived from the URL; the user can
  still edit it before submitting; an already-edited name is not overwritten by further
  URL changes.
- Submitting with a blank name still succeeds (backend derives the name).
- Submitting creates the project and shows a pending/cloning state in the sidenav.
- The UI reflects ready/failed status without a manual page reload (poll or push).
- A failed project offers retry and delete actions.

## Explicitly not
- Project entity/checkout backend logic — #42, already delivered.
- Sidenav issue grouping — #44, already delivered (this task builds on it).

## Decisions made along the way
- The "Add Project" button lives inside `SidenavComponent` itself (in its
  `.controls` row), not as a separate top-level component wired through
  `AppComponent` — the popup's only job is to POST and emit; the sidenav already
  owns the project list/reload logic it needs to refresh into, and routing the
  event up through `AppComponent` and back down would add indirection nothing
  else needs (haninaguib, 2026-08-26).
- Client-side name-prefill logic (`deriveProjectName`) duplicates
  `ProjectCheckoutService.deriveName` (#42) rather than calling the backend to
  preview it — it's a pure, small string transform, and the backend remains the
  actual source of truth (a blank name still round-trips through the real POST,
  which derives and persists the name server-side regardless of what the popup
  previewed).
- "Poll or push" (the issue's own wording) — chose polling: while any project in
  the sidenav's own list is `CLONING`, it re-fetches `/api/projects` (and
  everything downstream) every 3s until none are, then stops. No new backend
  push mechanism (WebSocket/SSE) was needed or added.
- Project status (cloning/ready/failed) is rendered per-section, replacing that
  section's issue tree while non-`READY` — a `CLONING`/`FAILED` project has
  nothing real to show yet under today's still-shared issue data (#43/#81 not
  landed), and showing the shared tree there would misleadingly imply the new
  project's own issues are already visible.
- Retry/delete require no confirmation for retry (an idempotent, low-stakes
  re-attempt) but delete uses a native `confirm()` — same pattern
  `ConsoleTabsComponent.closeTab` already uses for closing a session (#75) — no
  new confirmation UI invented for a codebase that already has this convention.

## Deviations / notes
- Manually verified in a real browser (not just the test suite), reusing the
  same isolated port/data-dir approach as #44 so as not to touch the human's own
  already-running dev instance: opened the popup, typed a real public repo URL
  (`github.com/octocat/Hello-World`) and watched the name field prefill live;
  submitted it and watched the new project clone for real, transition through
  `CLONING`, and settle to `READY` with its own collapsible section appearing
  automatically (no manual refresh) via the 3s poll. Separately submitted an
  unreachable URL and watched it settle to `FAILED` (shown in red) the same way,
  then clicked "retry" and confirmed a real `POST /api/projects/{id}/retry`
  fired and the project correctly re-settled to `FAILED` again (still a bad
  URL). "delete" is gated behind `confirm()`, which the automated browser
  auto-dismisses (no human to click OK) — confirmed no `DELETE` request fired in
  that case, matching the declined-confirmation behavior already covered by the
  unit tests; the confirmed-delete path is unit-tested but not separately
  driven through the real browser (haninaguib, 2026-08-26).
