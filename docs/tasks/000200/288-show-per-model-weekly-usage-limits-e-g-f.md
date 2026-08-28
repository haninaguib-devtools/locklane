# 288 — Show per-model weekly usage limits (e.g. Fable) in the usage widget
Issue: #288

## Asked
Anthropic's usage endpoint now reports a separate weekly usage limit for individual
models that get their own quota (confirmed live: a "Fable" entry sitting at 43% weekly
usage while the account-wide weekly figure was 35%). Extend locklane's usage widget to
show these per-model weekly limits alongside the existing session/weekly bars, sourced
generically from the response's `limits` array rather than as a hardcoded "Fable" field,
so a future scoped model shows up without further code changes.

## Done when
- The engine's Claude usage parsing reads the `limits` array from `/api/oauth/usage` (in
  addition to `five_hour`/`seven_day`) and extracts every entry with `group: "weekly"`
  and a non-null `scope.model` as a per-model weekly limit (percent, `resets_at`, the
  model's `display_name`).
- Exposed to the client as a list, not a named field per model.
- The usage widget renders one row per entry in that list, alongside the existing
  session/weekly bars, showing model name, percent used, and reset time.
- No active scoped-model entries leaves existing widget behavior unchanged — covered by
  a test.
- A test asserts a `limits` array with two synthetic scoped-model entries renders two
  rows.
- Any `limits` entry with an unrecognized shape, or any other unknown top-level response
  field, is ignored rather than failing the whole parse.
- `./mvnw -B test` passes, and the client test suite passes.

## Explicitly not
- Codex-side per-model breakdowns — Codex's usage response has no analogous concept.
- Surfacing credit/spend-based fields (`spend`, `extra_usage`).

## Decisions made along the way
- Reused `WindowUsage` (percentLeft + resetsAt) inside a new `ModelWeeklyLimit(modelName,
  window)` record, rather than a flat four-field record, so both engine and client reuse
  the existing per-window percent/reset-time formatting code unchanged (haninaguib,
  2026-08-28).
- The widget labels each per-model row `"<model> weekly"` and appends it after the
  existing 5-hour/weekly rows via the same generic `providerWindows()` list the template
  already iterates, rather than a separate template block (haninaguib, 2026-08-28).

## Deviations / notes
- Fixing the `ProviderUsage` TypeScript interface to require `modelWeeklyLimits` broke
  compilation of two spec files outside this task's declared client scope
  (`client/src/app/app.component.spec.ts`,
  `client/src/app/components/sidenav/sidenav.component.spec.ts`), which each hold one
  `ProviderUsage` fixture literal. Added `modelWeeklyLimits: []` to those four literals —
  a mechanical, meaning-free fix required by the interface change, not a feature change
  to those components.
