# 619 — Show a GitHub-unavailable error in the sidenav when the engine's issue refresh fails
Issue: #619

## Asked
When the engine cannot reach GitHub for a project (today: an expired or revoked
token), the person using the app must see that in the sidenav, instead of a sidenav
that silently keeps showing an hour-old picture. Today `GhIssueCache.refresh()`
swallows `GhUnavailableException`, logs a WARN, and returns `false`; both the
30-second scheduled poll and the sidenav's refresh button go through it, so a failure
produces an HTTP 200 carrying stale data indistinguishable from "up to date". The log
line stays; the failure must also reach the UI.

## Done when
- The engine records, per project, the outcome of its most recent GitHub refresh (at
  minimum: whether it failed, the failure text, and the time of the last successful
  refresh) and exposes it both on the issue-tree response and as a websocket event
  alongside `issuesChanged`. An engine unit test asserts a refresh that throws
  `GhUnavailableException` leaves the tree response carrying that failure, and that
  the next successful refresh clears it.
- The sidenav shows a visible, non-dismissable error for an affected project (failure
  text plus how long ago the last successful refresh was) that stays until a refresh
  succeeds; `sidenav.component.spec.ts` covers the failure appearing from the
  websocket event and clearing on a successful reload.
- Clicking the sidenav refresh button while GitHub is failing shows the failure (a
  fresh attempt is still made).
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.
- Human check: with an invalid token the error appears within ~30 s of engine start
  with no click; after re-adding the account under Settings → GitHub accounts it
  clears and the tree reflects GitHub.

## Explicitly not
- No change to how tokens are obtained, stored, or refreshed — sibling task #620.
- No change to the 30-second poll cadence; no webhooks.
- No new persistence: the status lives in memory alongside the cache.

## Decisions made along the way
- The tree endpoint now returns `{ nodes, github }` instead of a bare array, so the
  status rides on the same response the sidenav loads (agent, 2026-09-02). The
  client's `IssuesService.tree()` keeps returning `TreeNode[]` for the overview and
  project-summary callers; the sidenav uses the new `treeWithStatus()`.
- The status is also recorded on the cold-cache live fetch paths in `GhIssueCache`
  (`issues()` / `pullRequests()`), not only `refresh()`, so a project whose very
  first fetch fails at engine start is reported failing on its first tree load rather
  than only after the first scheduled poll (agent, 2026-09-02).
- The websocket event `githubRefreshStatus` is broadcast only when the outcome moves
  (failing ↔ ok, or the failure text changes), never on every successful 30 s poll
  (agent, 2026-09-02).

## Deviations / notes
- Touched outside the declared Scope, as a direct consequence of the tree response
  shape changing: `client/src/app/models/issue.model.ts` (the new
  `GithubRefreshStatus` / `TreeResponse` types the sidenav and service share), and the
  spec fixtures in `app.component.spec.ts`, `overview.component.spec.ts`, and
  `project-summary.component.spec.ts`, which fake that response and had to fake the
  new shape. No behaviour outside the scope changed — the overview and project
  summary still receive a bare `TreeNode[]` from `IssuesService.tree()`.
- `./mvnw -B test` fails locally with 9 failures in `ProjectCheckoutServiceTest`,
  `ProjectWorktreesServiceTest`, `WorktreeCreationServiceTest`, and
  `WorktreeCleanupSweeperTest` — the identical 9 fail on an untouched `origin/main`
  checkout on this machine (a global git `credential.helper=osxkeychain` and
  worktree-state assertions), so they are environmental, not this change. Every
  `github` package test passes; CI is the authority for the full set.
