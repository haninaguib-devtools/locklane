# 129 — Push issue-change events and auto-refresh the sidenav
Issue: #129 · Part of: #127

## Asked
The engine already refreshes its GitHub issue cache every 30 seconds
(`ProjectGhResources.refreshAll()` → `GhIssueCache`), but the browser never learns about
it — the sidenav is stale until the user clicks refresh. Make the cache refresh diff old
vs new state and, when something actually changed, publish an `issuesChanged` event (with
the project id) on the app-wide events channel from #128. The sidenav then re-fetches
that project's issue tree via the existing REST endpoint ("notify, then fetch" — the event
carries no issue data). On events-channel reconnect, the sidenav does one full reload to
catch up.

## Done when
- A change in the cached issue/PR set for a project results in exactly one
  `issuesChanged` event for that project; an unchanged refresh emits nothing. Covered by
  an engine test.
- The sidenav updates within one cache-refresh interval (~30s) of an issue changing on
  GitHub, with no user interaction. Manual check: open an issue with `gh issue create` in
  a test project and watch the sidenav update by itself.
- The sidenav reloads once after the events connection drops and reconnects.
- `./mvnw -B test` and the client test suite pass.

## Explicitly not
- No change to the 30s refresh cadence and no GitHub webhooks — freshness stays bounded
  by the existing engine poll.
- The manual refresh button stays (harmless, and useful for forcing an immediate engine
  fetch).

## Decisions made along the way
- `GhIssue`/`GhPullRequest` are records, so `refresh()` diffs old vs new state with plain
  structural `List.equals()` — no hand-rolled comparator needed (hani, 2026-08-26).
- `ProjectGhResources`'s test-only 3-arg constructor now builds its own no-op
  `EventBroadcaster` internally, rather than adding the broadcaster to every unrelated
  test's constructor call across `persistence` — those tests don't care about the events
  channel (hani, 2026-08-26).

## Deviations / notes
- `SessionRegistryReattachTest.closeStopsTheSessionAndForgetsItsRecord` fails on `main`
  too (verified in the sibling `locklane` checkout, unmodified) — pre-existing flake,
  unrelated to this task's scope.
