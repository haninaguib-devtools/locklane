# 4 — Cache GitHub API data to fix task-list sluggishness
Issue: #4 · Part of: #1

## Asked
The old locklane app's sluggishness is believed to come from live `gh` (GitHub
CLI/API) calls made on every task-list and task-detail view, not from the
Angular/Spring Boot architecture itself. Add caching/prefetching of GitHub issue data
in this rewrite's Spring Boot backend so the sidenav issue list and issue header load
without waiting on a live GitHub API round-trip every time.

## Done when
- Loading the sidenav issue list does not block on a live GitHub API call when a
  recent cached copy exists.
- Cached data is refreshed (poll or webhook-driven — implementer's choice, document the
  choice made) so the list does not go stale indefinitely.
- A cold cache (first run, or cache cleared) still produces a correct list by falling
  back to a live fetch.

## Explicitly not
- Caching strategy specifics (poll interval, invalidation approach) were not pinned by
  the issue — decided during implementation, see below.

## Decisions made along the way
- Fetch mechanism: shell out to the `gh` CLI (`gh issue list --state all --limit 1000
  --json ...`), same as this project's own pipeline already requires
  (`docs/adapters/TRACKER.md`) — no separate GitHub token/client setup needed. The old
  locklane repo's `engine/` has this exact pattern already
  (`dev.locklane.engine.projection.CliGhClient`, referenced for the architecture shape
  only — interface + CLI implementation, so the cache is testable against a fake — not
  copied; its `ProjectionService` explicitly documents "All data is fetched per
  request — the projection stores nothing", i.e. no caching, which is consistent with
  this issue's premise that the old app is slow because nothing is cached)
  (haninaguib, 2026-08-25).
- Caching strategy: polling, not webhooks. A `@Scheduled` background refresh every 30s
  (`GhIssueCache`), chosen over webhooks because receiving a GitHub webhook needs a
  publicly reachable endpoint and signature verification — real infrastructure this
  Phase 0 solo project doesn't have yet. Documented per the issue's own instruction
  (haninaguib, 2026-08-25).
- On a cache miss (nothing fetched yet), `issues()`/`issue()` fetch live and populate
  the cache synchronously, rather than returning empty — satisfies the "cold cache
  still produces a correct list" done-when bullet without a separate code path
  (haninaguib, 2026-08-25).
- A failed background refresh keeps serving the last successfully cached data rather
  than clearing it, so a transient `gh`/network hiccup does not empty the sidenav
  (haninaguib, 2026-08-25).
- Response shape: a single `GhIssue` record (number, title, state, labels, body,
  createdAt, updatedAt) serves both the list and the detail endpoint — one cached
  fetch backs both, mirroring how the old app's `ProjectionService.caseView` looks a
  single issue up within the already-fetched list rather than issuing a second gh
  call. Did not build the old app's richer tree/stage-derivation mapping
  (`TreeResponse`/`CaseResponse`, flow-state/blocked-by logic) — that is
  presentation/business logic beyond this issue's Scope ("GitHub data fetch/cache
  layer feeding the issue list and issue detail endpoints"), left for whichever task
  wires the actual client UI to real data (haninaguib, 2026-08-25).
- Endpoints: `GET /api/issues` (list) and `GET /api/issues/{number}` (404 if unknown)
  in a new `IssueController` (haninaguib, 2026-08-25).
- Verified the real `gh` integration manually (not just the fake-backed unit tests):
  started the app, confirmed `/api/issues` returns real live issue data and
  `/api/issues/{number}` 200s/404s correctly, then confirmed two repeat requests both
  completed in ~15ms — consistent with being served from the in-memory cache rather
  than re-shelling to `gh` (a live `gh issue list` call takes much longer)
  (haninaguib, 2026-08-25).

## Deviations / notes
- none
