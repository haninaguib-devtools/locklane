# 991 — Cut GitHub API usage of the issue/PR poll
Issue: #991

## Asked
Cut how often and how heavily the engine calls GitHub to keep its issue/PR cache current. Today `ProjectGhResources.refreshAll` runs every 30 s for every READY project, whether or not any browser is connected, and each run shells out to `gh issue list --state all --limit 1000 --json …,body,…` plus `gh pr list --state all --limit 1000`. gh pages those at 100 per request, so a repo with ~1000 issues and PRs costs ~10 GitHub API requests per run — ~1200 per hour per project — to re-download data that is usually unchanged. And anything past the 1000th issue or PR is silently dropped from the cache.

Four changes:
1. **Cheap change probe.** Before any real fetch, make one conditional request (ETag / `If-None-Match`) that tells whether anything in the repo's issues or PRs changed since the last successful fetch. A `304 Not Modified` does not count against GitHub's rate limit; when it comes back, the tick does no further GitHub calls.
2. **Incremental fetch.** When something did change, fetch only issues/PRs updated since the last successful fetch (e.g. REST `issues?state=all&since=<ts>`, and PRs sorted by `updated` desc, stopping at the first one older than that) and merge them into the cached lists, instead of re-downloading everything. A cold cache, and a forced refresh (`fresh=true`) where needed, still does a full fetch — paginated to the end, with no 1000-item cap.
3. **Back off when nobody is watching.** When no client is connected to the events WebSocket, poll at a slower interval (default 5 min, configurable in `application.yml`); return to the 30 s cadence as soon as a client connects, with one immediate refresh on that connect so the first view is current.
4. **No bulk body re-download.** Issue bodies are no longer re-fetched for unchanged issues on every tick. Bodies still reach the cache for new/changed issues (the tree's legacy "Part of: #n" fallback depends on them), or are fetched on demand where only the detail view needs them.

Everything that relies on the cache must behave as before: `issuesChanged` and `githubRefreshStatus` broadcasts, the bad-credentials renewal-and-retry path (#656), keep-serving-last-good-data on failure, the cold-cache fallbacks, per-project token (`GH_TOKEN`) and working-directory scoping, the bounded/killable subprocess (#763), native parent nesting (`GhIssue.parent`), `wip/<id>-` PR-to-issue matching (needs PR `headRefName`), draft state, forced refresh from the sidenav, and label edits' refresh.

## Done when
- With a warm cache and no change on GitHub, one refresh tick of a project makes exactly one GitHub request, and it is conditional (unit test with a fake gh/transport asserting the request count and the `If-None-Match` header).
- After one issue changes, the next tick fetches only the changed item(s) and the cached lists equal what a full fetch would produce (unit test).
- A repo with more than 1000 issues is cached in full (unit test with a paged fake returning >1000 items).
- With zero events-WebSocket clients connected, the scheduled tick polls at the configured slow interval; connecting a client triggers an immediate refresh and restores the 30 s cadence (unit test on the scheduling decision).
- Existing tests in `engine/src/test/java/dev/locklane/engine/github/` still pass unchanged in intent; `mvn -q -pl engine verify` passes (env-only failures noted, not hidden).

## Explicitly not
- GitHub webhooks or a GitHub App — the engine is a local server with no public URL.
- The hourly release check (`ReleaseUpdateChecker` / `CliReleaseClient`), token renewal, and `git fetch` in worktree creation/cleanup — already cheap.
- Any change to the Angular client or the REST/WebSocket contract it uses.

## Decisions made along the way
- **Probe:** `gh api -i [-H If-None-Match: <etag>] repos/{owner}/{repo}/issues?state=all&sort=updated&direction=desc&per_page=1`.
  The issues endpoint lists PRs too, so its ETag moves when any issue or PR does. gh exits 1 on a
  304 ("gh: HTTP 304"); the status line `-i` prints first tells that apart from a real failure.
  Checked live: a matching ETag answered 304 and `X-RateLimit-Used` did not move.
- **Watermark:** the probe also returns the newest `updated_at` (GitHub's own clock, so no clock
  skew). The next incremental fetch asks for items updated at or after it. `since` is inclusive,
  so the boundary item is fetched again; the merge makes that harmless.
- **Fetches use GraphQL** (`gh api graphql`), paged by the engine itself, 100 per call, until the
  last page — no 1000 cap, and every page gets its own 60 s timeout. Same fields and value shapes
  as `gh issue list --json` / `gh pr list --json` (state `OPEN`/`CLOSED`/`MERGED`, `parent`,
  `author`, `headRefName`, `isDraft`). Issues: `filterBy: {since}`. PRs have no `since` filter, so
  they page newest-updated first and stop at the first older one. Checked live on this repo: 522
  issues and 469 PRs, the same counts `gh issue list`/`gh pr list` give.
- **Cache order:** an incremental-capable client's lists are kept newest number first, after a
  full fetch and after a merge alike, so both give equal lists. The tree sorts by `createdAt`
  itself, so nothing depended on gh's order. Clients without incremental support (all the test
  fakes) keep today's exact path and order.
- **The ETag and watermark are stored only after the fetch succeeds**, so a failed fetch leaves the
  next probe still seeing the change. `GhIssueCache.refresh` is synchronized so the poll and a
  request-driven refresh cannot interleave that state.
- **Three refresh modes:** the poll uses `refresh()` (conditional probe, incremental).
  `tree?fresh=true` uses `refreshFully()` (full fetch — the only way a deleted or transferred issue
  leaves the cache). A label edit uses `refreshAfterWrite()` (unconditional probe, incremental), so
  a probe listing that lags the write for a moment cannot answer 304 and hide the new labels.
- **Bodies:** fetched only for issues in a full fetch or updated since the watermark; no on-demand
  body fetch was needed.
- **Idle back-off:** the `@Scheduled` tick still fires every 30 s but polls only when
  `refreshDue()`: a client is connected, or the idle interval
  (`locklane.github.issue-poll.idle-interval-ms`, default 300000) has passed since the last poll.
  The first tick after startup is always due (#786 unchanged).
- **Refresh on connect:** `EventBroadcaster` gained `connectedClientCount()` and
  `onClientConnected(listener)`. When a client connects and the last poll is older than 30 s, one
  refresh runs at once on its own virtual thread (never the handshake thread). A lock keeps it and
  the scheduled tick from running together; a connect while a refresh is running skips.
  The listener is registered only by the Spring constructor, so the existing tests' mock
  broadcasters see no new interaction.

## Deviations / notes
- `EventBroadcaster` got a connect listener, not only the read-only count the issue's Scope names.
  It is in a Scope file and is the smallest hook that gives "immediate refresh on connect".
- `CliGhClientTest`'s two issue-list fixtures were rewritten from `gh issue list` JSON to the
  GraphQL response shape; what they test (stderr flood, author mapping) is unchanged.
- Known limit: an incremental fetch cannot see a deleted or transferred issue; it stays until the
  next full fetch (sidenav refresh or engine restart).
- `scripts/check.sh` (`./mvnw -B test`): all 18 `github` test classes pass; 1092 run, 17 failures +
  3 errors, all environment-only on this Mac. 6 of them (`EventsWebSocketHandlerTest`,
  `TerminalWebSocketHandlerIntegrationTest` x2, `ProjectAgentSessionWebSocketIntegrationTest` x2,
  `SessionRegistryReattachTest`) were re-run on clean `origin/main` and fail there identically — a
  real PTY never answers in time here. The other 14 (`ProjectCheckoutServiceTest` x3,
  `ProjectWorktreesServiceTest` x4, `WorktreeCleanupSweeperTest` x3, `WorktreeCreationServiceTest`,
  `BellHookCommandDetachedShapeTest` x2 — no `setsid`, `ProcessTreesTest` — no `/bin/true`) are this
  machine's known environment failures, in code this task does not touch.

## Agents
- work: claude-code / claude-opus-5-5
