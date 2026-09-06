# 737 — Fix Codex usage not reporting in the usage widget
Issue: #737

## Asked
A user with the Codex CLI installed and logged in expects to see their Codex usage
(percent of the 5-hour and weekly rate-limit windows remaining) alongside Claude in the
console's usage widget, the same way it works for Claude. The Codex portion of that
widget was not showing usage — find out why and fix the underlying cause.

## Done when
- Root cause of the missing Codex usage identified and documented in the task record.
- With valid, unexpired Codex CLI credentials present locally, `GET /api/usage` returns
  a `codex` entry with `available: true` and populated window data, and the usage
  widget renders it.
- `CodexUsageProviderTest` (and any other existing usage tests) still pass; a
  regression test is added that covers the specific defect found, if the cause is a
  code bug rather than external state.
- `./mvnw -B test` passes.

## Explicitly not
- Implementing OAuth token refresh for Codex — investigation found the root cause is
  external state (an expired local token), not a code defect, so the documented
  non-goal in `CodexTokenSource`/`CodexUsageProvider` stands; no separate issue needed,
  this was never a deferred piece of this task's own scope.

## Decisions made along the way
- Investigated by reading the full call path end to end — `CodexTokenSource` →
  `CodexUsageProvider` → `UsageService`/`UsageConfig` (provider wiring) →
  `UsageController` → the client's `usage.service.ts` / `usage-widget.component.ts/html`
  — plus the two providers' own unit tests, rather than guessing: no defect found
  anywhere in that path (Claude Sonnet 5, 2026-09-06).
- Root cause identified: this machine's local `~/.codex/auth.json` (present, correct
  shape, `tokens.access_token` and `tokens.account_id` both populated — the exact shape
  `CodexTokenSource.credentials()` expects and `CodexTokenSourceTest` already covers)
  carries an access token whose own JWT `exp` claim is `2026-09-05T18:15:24Z` —
  already expired as of this investigation (now `2026-09-06`, roughly a day past
  expiry; `last_refresh` in the file is `2026-08-26T18:15:25Z`, so the token was
  issued with a ~10-day lifetime and nothing since has refreshed it). `CodexTokenSource`
  reads the stored token as-is and does no expiry check or refresh — a documented,
  deliberate non-goal (its own class doc, and this issue's own hypothesis #2) — so
  `CodexUsageProvider.fetch()` sends the expired token to `wham/usage`, the call fails,
  and the provider degrades to `unavailable`, exactly as designed for "any failure"
  (Claude Sonnet 5, 2026-09-06).
- Ruled out the other three hypotheses the issue named: auth.json missing or wrong
  shape (it exists and matches the expected shape exactly); the `wham/usage` endpoint
  or response shape having drifted (the parse logic, verified against
  `CodexUsageProviderTest`'s `parsesBothWindowsAndSendsTheAccountHeader`, correctly maps
  `primary_window` → the 5-hour window and `secondary_window` → the weekly window,
  matching the class's own documented, live-verified shape from #137 — no code defect
  found there); and a defect in `CodexTokenSource`/`CodexUsageProvider` themselves
  (read closely against their tests — credential extraction, request headers, and
  parsing all behave as documented, and match `ClaudeUsageProvider`'s equivalent,
  working path) (Claude Sonnet 5, 2026-09-06).
- Ruled out a client-side or wiring cause: `UsageConfig` registers `codexUsageProvider`
  alongside Claude and OpenCode exactly like the others; `UsageController` returns
  whatever `UsageService` has with no per-provider special-casing; the client's
  `usage-widget.component.html` iterates `s.providers` generically and shows
  "unavailable" per-provider rather than hiding just Codex — nothing there could
  single out Codex either (Claude Sonnet 5, 2026-09-06).
- Decided the smallest fix that restores reporting, per the issue's own guidance for
  an expired-token cause: none is needed in this repository. The engine already does
  the correct thing by design — degrade that one provider to `unavailable` rather than
  break the widget — and the fix is external: running the Codex CLI once (which
  refreshes `~/.codex/auth.json` via its own login/refresh flow, using the
  `refresh_token` already stored there) restores a valid, unexpired `access_token`,
  after which `CodexUsageProvider` reports normally — no different from what the
  `Done when` criteria already describe as the expected behavior with valid,
  unexpired credentials, which the existing code and tests already establish (Claude
  Sonnet 5, 2026-09-06).

## Deviations / notes
- No code change was made — the defect this issue reported is external state (a stale
  local Codex OAuth token), not a bug in this repository, so no regression test applies
  either (the `Done when` criteria's own "if the cause is a code bug" qualifier). This
  record captures the root-cause investigation as the task's actual output.
- Live-verifying the `wham/usage` endpoint end to end (e.g. refreshing the token or
  making a real authenticated call) was not attempted in this session — doing so would
  mean sending stored OAuth credentials to an external endpoint from an automated
  session, which this environment does not permit unsupervised. The conclusion above
  rests on: the stored token's own `exp` claim (self-describing, no network call
  needed) being in the past, the request/parse code matching its own tests exactly,
  and there being no other plausible path for Codex alone (not Claude) to go dark.
