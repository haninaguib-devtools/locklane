# 545 — Make the side nav refresh button bypass the issue cache and broadcast changes
Issue: #545

## Asked
Clicking the side nav's refresh button should show the current issue list, not the
cached one. Today the button calls `load()` in `sidenav.component.ts`, which fetches
every project's tree with the default `fresh=false`, so it re-renders whatever
`GhIssueCache` already holds. When an agent in a console has just created an issue via
`gh`, the button does nothing visible until the 30-second scheduled poll runs. The
bypass already exists: `GET /api/projects/{id}/issues/tree?fresh=true` forces a live
fetch before serving, and the sidenav already uses it when leaving a project console
page. The button should use it too. Separately, that forced-refresh path in
`IssueController.tree` ignores the boolean `GhIssueCache.refresh()` returns, so a
change discovered by a forced refresh is never broadcast as `issuesChanged` and other
open tabs stay stale; it should broadcast the same way the scheduled
`ProjectGhResources.refreshAll` does.

## Done when
- `SidenavComponent.refresh()` (the button handler) results in a `tree?fresh=true`
  request for each project it loads; `ngOnInit`'s initial load and the
  `EventsService.reconnected$` reload keep requesting the tree without `fresh` (cache
  reads).
- `IssueController.tree` with `fresh=true` broadcasts `issuesChanged` with `projectId`
  via `EventBroadcaster` when `cache().refresh()` returns `true`, and does not
  broadcast when it returns `false`.
- A unit test on the client asserts the button path passes `fresh=true` and the
  initial load does not; a unit test on the engine asserts the
  broadcast-on-change / no-broadcast-on-no-change behaviour of the `fresh=true` path.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- No change to the 30-second scheduled poll interval or to `GhIssueCache` itself.
- No automatic detection of issue creation from console output; the refresh stays a
  manual click plus the existing poll.

## Decisions made along the way
- `load()` gained a second `fresh` parameter (default `false`) threaded through to
  `IssuesService.tree()`; only `refresh()` passes `true` — `ngOnInit`, the
  `reconnected$` reload, and the cloning poll all keep the default (Hani, 2026-09-02).
- `IssueController` now takes `EventBroadcaster` as a constructor dependency,
  mirroring `ProjectGhResources`'s existing pattern; `IssueControllerRoutingTest`
  (a `@WebMvcTest` slice) needed a new `@MockitoBean` for it since a plain
  `@Component` isn't pulled into a web slice automatically (Hani, 2026-09-02).

## Deviations / notes
none
