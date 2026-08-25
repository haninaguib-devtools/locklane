# 16 — Fetch PR/checks data and expose per-issue flow-state and detail
Issue: #16 · Part of: #1

## Asked
Extend the backend's GitHub data layer (`dev.locklane.engine.github`, added in #4) to
also fetch PR data (`gh pr list` / `gh pr view`) and expose a per-issue detail
endpoint (e.g. `GET /api/issues/{number}/detail`) returning: the task record path, a
checks summary (pass/fail/pending counts), branch & PR info, and enough to derive
flow-state (open/plan/work/review/ship). Discovered as a gap while starting #3
(client UI), whose flow-state strip and "?" details popup need this data and which
`GET /api/issues` (#4) does not provide.

## Done when
- `GET /api/issues/{number}/detail` returns the task record path (or an explicit
  "no record yet" value), a checks summary, branch & PR info, and the data needed to
  derive `work`/`review`/`ship` flow-state — for an issue with an open PR, a merged
  one, and one with no PR at all.
- The new PR-fetching path is unit-testable against a fake, the same way
  `GhIssueCacheTest` (#4) tests against a fake `GhClient` — no test shells out to the
  real `gh` process.

## Explicitly not
- Any client-side change — split to #3.
- Worktree/session data — split to #15.

## Decisions made along the way
- Extended `GhClient`/`CliGhClient`/`GhIssueCache` (from #4) rather than a parallel
  set of PR classes: `pullRequests()` follows the exact same fetch/cache pattern as
  `issues()`, refreshed together on the same 30s scheduled tick. `pullRequestDetail()`
  (reviews/checks for one specific PR) is deliberately NOT cached — it's a live call
  made only when a client asks for that specific issue's detail, matching how the old
  locklane repo's `ProjectionService.caseView` also fetches PR detail per case-view
  rather than prefetching it for every PR (haninaguib, 2026-08-25).
- Issue↔PR correlation: match a PR's `headRefName` against the `wip/<id>-<slug>`
  branch convention (`AGENTS.md`) via regex, same as the old repo's
  `ProjectionService.prsByTask()`. The newest PR wins if an issue ever had more than
  one (haninaguib, 2026-08-25).
- `recordPath()` mirrors the old repo's `recordPath()` lookup shape (walk
  `docs/tasks/<bucket>/` for a `<number>-*.md` match) but needed a way to locate the
  repo root: the engine's working directory is `engine/` when launched via `mvn
  spring-boot:run` from the repo root (or `mvn -f engine/pom.xml`), not the repo
  root itself. Added a `locklane.project-root` property (default `${user.dir}/..`,
  same configurable-property pattern as `locklane.data-dir` from #6) rather than
  hardcoding or walking up looking for a marker file — documented as needing an
  override for other launch shapes (haninaguib, 2026-08-25).
- Flow-state derivation: `open` always true; `plan` true if the issue body has a real
  `## Plan` heading (see the bug found and fixed below); `work` true if a PR exists;
  `review` true if the PR has at least one review; `ship` true if the PR is merged or
  the issue is closed. A closed issue or merged PR marks every earlier step true too
  (haninaguib, 2026-08-25).
- **Bug found via manual smoke-testing against real data, fixed before shipping**: the
  first `plan` check used `body.contains("## Plan")` (matching the old app's own
  reference logic) — a plain substring match. Testing against this task's own real
  issue (#16) exposed a false positive: #16's body *mentions* "`## Plan`" in prose
  (describing this very feature) without containing an actual heading, and the
  substring check misread that as a completed plan step. Fixed with a regex requiring
  `^## Plan` at the start of a line (multiline), added a regression test
  (`merelyMentioningPlanInProseDoesNotCountAsHavingOne`), and re-verified against
  live issue #16 data that `plan` now correctly reads `false`
  (haninaguib, 2026-08-25).

## Deviations / notes
- none
