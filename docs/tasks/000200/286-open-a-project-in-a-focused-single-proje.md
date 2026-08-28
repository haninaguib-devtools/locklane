# 286 — Open a project in a focused single-project window

Issue: #286

## Asked
Let someone open a single project in its own separate browser window, where the sidenav
shows only that one project instead of every project in the workspace — a focused view
for working on one project at a time. Locklane is a plain Angular web app (no Electron),
so "separate window" means a real browser window/tab opened with `window.open()`, with
the focused state carried in the URL rather than any shared in-memory service — matching
how the rest of the app already derives selection from the route.

## Done when
- A "pop out" control appears on each project's row in the sidenav header, alongside the
  existing new-console (+), retry, and delete controls.
- Clicking it opens that project's current route in a new browser window/tab via
  `window.open()`, carrying a query param (e.g. `focus=1`) that marks the window as
  single-project-focused.
- When that query param is present: the sidenav renders only the one focused project's
  section — it does not fetch or list any other project — and the topbar's
  "+ add project" control is hidden.
- With no such query param, the app's existing behavior (every project listed in the
  sidenav, full topbar) is unchanged — this is judged by a human comparing before/after.
- `./mvnw -B test` and the client (Angular) test suite pass, and
  `./scripts/consistency-check.sh` passes.

## Explicitly not
- No native/Electron window management — this stays a plain browser `window.open()`.
- No syncing between windows (e.g. collapsing or deleting a project in the main window
  does not need to reach an already-open focused window) — each window already
  independently re-derives its state from its own URL and its own fetches, the same as
  today.
- No change to how pinning, collapsing, or filtering state is stored (existing
  localStorage-backed stores are unaffected).

## Decisions made along the way
- The pop-out control's target URL: when the project it belongs to is the one currently
  open (its issue or its own summary page selected), the new window gets the browser's
  actual current URL (preserving whatever issue/console route is showing) with `focus=1`
  appended/merged into its query params. For a project row that is not the currently
  active one, the new window instead gets that project's base route
  (`/projects/:projectId/issues`), the same route clicking the project's name already
  navigates to — there is no other "current route" to carry for a project that isn't
  the one currently on screen (hani, 2026-08-28).
- In focus mode the sidenav still calls `GET /api/projects` (the list endpoint) and
  filters it down to the one focused project client-side, rather than skipping that
  call — there is no single-project GET endpoint on the engine (out of scope per the
  issue's "No engine changes expected"), and `ProjectSummaryComponent` already uses this
  same list-then-find pattern to resolve one project's metadata. What focus mode avoids
  is fetching every *other* project's issue tree (the expensive per-project fetch) and
  rendering their sections (hani, 2026-08-28).
- Query-param detection reads `ActivatedRoute.snapshot.queryParamMap` directly (query
  params are shared across the whole route tree from the root snapshot in this app's
  routing setup), mirroring how `AppComponent` already re-derives `selectedProjectId`
  etc. from `router.events` on every `NavigationEnd` (hani, 2026-08-28).

## Deviations / notes
- none
