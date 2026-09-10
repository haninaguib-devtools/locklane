# 848 — Fable weekly usage never shows: the usage API reports percent, not utilization, inside limits
Issue: #848

## Asked
The usage widget under Claude is meant to show one extra weekly row per model that has its own scoped quota (today: "Fable"), the same way Claude Desktop's Usage settings page does — #288 built the whole path for this, engine and client. It never renders, because `ClaudeUsageProvider.modelWeeklyLimits` reads each `limits` entry with the same reader as the top-level `five_hour`/`seven_day` windows, which requires a `utilization` key — and the live `https://api.anthropic.com/api/oauth/usage` response carries the percentage in `limits` entries under `percent`, not `utilization`. The reader returns null, the entry is silently skipped, and the row is lost. The `ClaudeUsageProviderTest` fixture was written with `utilization` inside `limits`, so the tests pass against a shape the API does not send. A verified live entry, for reference:

```json
{"kind": "weekly_scoped", "group": "weekly", "percent": 37, "severity": "normal",
 "resets_at": "2026-09-15T20:59:59.678487+00:00",
 "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null}, "is_active": true}
```

Make the parser read `percent` from `limits` entries (keeping `utilization` as a fallback, so the parse degrades rather than breaks if the field is renamed back), correct the test fixture to the real shape, and add a case that proves a `percent`-only entry is read. Optionally also require `kind == "weekly_scoped"` alongside `group == "weekly"`, which is the stricter signal the live response gives; if so, cover it in the tests. No client change is needed — the widget already renders every entry in `modelWeeklyLimits` as `<model> weekly`.

## Done when
- `ClaudeUsageProviderTest` has a fixture whose `limits` entries use `percent` (no `utilization`) with the live shape above, and asserts a `ModelWeeklyLimit` named `Fable` with `percentLeft` 63 is produced.
- `./mvnw -B test -pl engine -am -Dskip.npm -Dtest=ClaudeUsageProviderTest` passes.
- Against a live Claude Max account with a scoped Fable limit, the expanded usage widget shows a "Fable weekly" row under Claude with the same percentage Claude Desktop's Usage page shows for Fable.
- The class-level Javadoc in `ClaudeUsageProvider` describes the `limits` entry shape correctly (`percent`, not `utilization`).
- A `CHANGELOG.md` entry under the unreleased section.

## Explicitly not
- No change to the client widget, the `ProviderUsage`/`ModelWeeklyLimit` records, or the wire shape of `/api/usage`.
- No display of the `spend`/`extra_usage` credits section or the `severity`/`is_active` fields.
- No change to how the OAuth token is sourced.

## Decisions made along the way
- Unified `window()` reader accepts `percent` (preferred) with `utilization` fallback, so top-level windows and `limits` entries share one reader; did not add the optional `kind == weekly_scoped` requirement to keep the fix minimal.
- Updated the `ignoresLimitsEntries` fixture to the live `percent` shape; kept filtering semantics unchanged.
- Added `## Unreleased` CHANGELOG section since none existed; the issue's Done-when requires an entry there even though ordinary task PRs normally leave CHANGELOG to the release-cut task.

## Deviations / notes
- none

## Checks run
- `./mvnw -B test -pl engine -am -Dskip.npm -Dtest=ClaudeUsageProviderTest -Dsurefire.failIfNoSpecifiedTests=false` — PASS (10 tests) — target test from the issue.
- `./mvnw -B test` — FAIL on 4 pre-existing environment-related tests unrelated to this diff (`ProjectCheckoutServiceTest` x3 GH_TOKEN/credential-helper leakage, `ProjectAgentSessionWebSocketIntegrationTest` x1 timeout); same 4 failures documented on main in the v0.2.27 release record. `ClaudeUsageProviderTest` passes inside the full run.
