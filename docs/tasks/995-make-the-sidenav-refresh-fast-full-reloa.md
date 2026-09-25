# 995 — Make the sidenav refresh fast; full reload once a day
Issue: #995

## Asked
Make the sidenav's refresh button fast, and move the full re-fetch of every issue and PR to a daily schedule. Since #991, `GET /api/projects/{id}/issues/tree?fresh=true` (the sidenav refresh button) calls `GhIssueCache.refreshFully()`, which re-downloads every issue and PR in pages of 100, one `gh` call per page, in sequence — about 11 calls and ~10 s on a repo with ~500 issues and ~470 PRs. The button should instead do exactly what the scheduled poll does, `GhIssueCache.refresh()`: one conditional change probe (a `304` ends it, at no rate-limit cost), otherwise fetch and merge only the issues/PRs updated since the last fetch. Closing, reopening, editing, labelling, re-parenting an issue, and any PR state change all bump `updated_at`, so the button still reflects them. The only thing an incremental refresh cannot see is an issue that was deleted or transferred to another repo; to clean those up, a full re-fetch (`refreshFully()`) runs when the server starts (a cold cache already does this today) and then whenever 24 hours have passed since a project's last full fetch, checked by the scheduled poll in `ProjectGhResources`. The 24 h interval is configurable in `application.yml`. Everything else about refreshes stays as #991 left it: `issuesChanged` and `githubRefreshStatus` broadcasts, the bad-credentials renewal-and-retry path, keep-serving-last-good-data on failure, idle back-off, refresh on client connect, and label edits' `refreshAfterWrite()`.

## Done when
- A unit test shows `tree?fresh=true` on a warm cache with nothing changed makes exactly one GitHub call (the conditional probe) and no full fetch.
- A unit test shows the scheduled poll does a full fetch once 24 h (the configured interval) have passed since a project's last full fetch, and an incremental one before that.
- A unit test shows an issue closed on GitHub appears closed after one `fresh=true` refresh.
- Existing tests in `engine/src/test/java/dev/locklane/engine/github/` still pass unchanged in intent; `scripts/check.sh` passes (environment-only failures named and shown identical on `origin/main`).

## Explicitly not
- Any change to the Angular client or the REST/WebSocket contract.
- Detecting deleted or transferred issues between daily full fetches.

## Decisions made along the way
- `tree?fresh=true` now calls `GhIssueCache.refresh()`, the poll's own refresh: conditional probe,
  then incremental. It trusts a 304 — the human chose that over skipping the probe, accepting a
  small chance that GitHub's listing lags a change by a moment.
- The daily full fetch lives in `GhIssueCache.refreshOnSchedule(interval)`: full when no full
  fetch has succeeded within the interval, incremental otherwise. It remembers when the last full
  fetch succeeded (cold-cache and forced alike). A cold cache's first refresh is full anyway, so
  the server's first poll after starting is full with no extra code.
- `ProjectGhResources.refreshAll` (the poll, and its bad-credentials retry) calls
  `refreshOnSchedule`; the interval is `locklane.github.issue-poll.full-refresh-interval-ms`
  (default 86400000). With the idle back-off, an idle engine's full fetch can land up to one idle
  interval (5 min) past the 24 h mark.
- Label edits still use `refreshAfterWrite()`; `refreshFully()` stays, now reached only from the
  schedule.

## Deviations / notes
- `GhIssueCacheIncrementalTest`'s `FakeRepo`, `Stamped`, `issue()`, `pr()` and
  `ProjectGhResourcesSchedulingTest`'s `MutableClock` went from private to package-private so the
  new tests reuse them; no behaviour change.
- `scripts/check.sh` (`./mvnw -B test`): all 19 `github` test classes pass; 1095 run, 13 failures +
  3 errors, all environment-only on this Mac and all among the set #991's record showed failing
  identically on `origin/main` or listed as this machine's known failures (PTY timing, no
  `setsid`, no `/bin/true`, git credential helper setup). None touch code this task changes.

## Agents
- work: claude-code / claude-opus-5-5
