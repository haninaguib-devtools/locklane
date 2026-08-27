# 137 — Show remaining Claude and Codex usage limits in the sidebar
Issue: #137

## Asked
Add a small read-only "usage" widget pinned at the bottom of the client sidebar
showing how much of the user's Claude (Claude Code subscription) and Codex usage
limits remain. Collapsed, it is one thin row with the word "usage" and two tiny
progress bars (green for Claude, amber for Codex; color shifts toward the accent as
a limit runs low). Clicking expands it in place: per tool, the 5-hour window as the
main bar with "N% left" and its reset time, plus the weekly limit as a secondary
line, and an "updated N min ago" footer. The engine reads each CLI's locally stored
OAuth token and calls the same undocumented usage endpoints those CLIs' own
`/usage`/`/status` screens use, degrading any failure to a per-provider
"unavailable" rather than an error or a crash, and caches responses so the widget
can poll cheaply.

## Done when
- The sidebar shows the collapsed usage row when the engine returns data for at
  least one provider, and expands/collapses on click.
- The expanded panel shows, per provider: 5-hour window percent left, its reset
  time, weekly percent left, and a last-updated line.
- A provider whose token is absent or whose endpoint call fails shows
  "unavailable" for that provider only; the other provider and the rest of the
  sidebar are unaffected (verified by a test with the provider stubbed to fail).
- The engine exposes one JSON endpoint for the widget, with caching so repeated
  widget polls within the cache window make no upstream call (verified by test).
- No token or credential value ever appears in the JSON response, logs, or client
  code.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
- No writing or refreshing of either CLI's credentials — read-only use of what is
  already on disk; an expired token just fails the upstream call and shows
  "unavailable".
- No cost/spend estimation from local transcript files — server-reported limit
  percentages only.
- No settings UI for enabling/disabling providers.

## Decisions made along the way
- The exact endpoints are undocumented by both vendors, so they were found by
  inspecting the locally installed `claude` and `codex` CLI binaries (`strings`
  over the binaries, since `claude` is a compiled executable and `codex`'s native
  binary is a Rust build) rather than guessed (haninaguib, 2026-08-26, per an
  explicit ask to "find out" rather than use placeholders):
  - Claude: `GET https://api.anthropic.com/api/oauth/usage`, bearer-authenticated
    with the token from `~/.claude/.credentials.json` (`claudeAiOauth.accessToken`)
    or, as a fallback, the macOS Keychain service `"Claude Code-credentials"`. Its
    response shape (`rate_limits.five_hour` / `rate_limits.seven_day`, each with
    `used_percentage` and a Unix-epoch-seconds `resets_at`) is corroborated by the
    CLI's own bundled documentation for its statusline hook's JSON input, which
    this data feeds — high confidence.
  - Codex: `GET https://chatgpt.com/backend-api/wham/usage`, bearer-authenticated
    with `~/.codex/auth.json`'s `tokens.access_token`, plus a required
    `chatgpt-account-id` header from `tokens.account_id`. Its response fields
    (`primary`/`secondary` windows, each with `used_percent` and `resets_at`) are
    inferred from the CLI's own internal struct and HTTP-header names
    (`RateLimitWindow`, `x-codex-primary-used-percent`, etc.) found in the binary —
    lower confidence than Claude's, since there is no equivalent bundled
    documentation to cross-check the exact response envelope against. Both
    providers parse defensively and degrade to "unavailable" on any shape mismatch,
    which is the correct behavior either way per the Goal.
- The usage widget lives inside `SidenavComponent`'s own template, in a new
  `.sidenav-scroll` wrapper around the existing scrollable content, so the widget
  itself sits outside that scroll area and stays pinned to the bottom of the
  sidebar rather than scrolling away with the case list.
- `/api/usage` was added to `SecurityConfig`'s authenticated matchers (alongside
  `/api/auth/me`) since it reads this host's own CLI credentials, even though it
  returns no per-project data.

## Deviations / notes
- Widget polls the engine every 60 seconds on its own timer, independent of
  `SidenavComponent`'s own project-list refresh, relying on the engine's 3-minute
  cache to keep that cheap.
- `sidenav.component.spec.ts` and `app.component.spec.ts` needed updates for the
  widget's own `/api/usage` fetch: an explicit flush was added everywhere the
  sidenav is mounted, plus a defensive drain of any leftover `/api/usage` request
  in `afterEach` — some of these tests' own `tick()` calls (unrelated to the
  widget) turned out to fast-forward the widget's 60-second poll timer within
  `fakeAsync`, which would otherwise leave a second, unflushed request at
  `httpMock.verify()` time.
- The branch was 5 commits behind `main` by the time this landed (#134, #136,
  #144, #146, #147 merged while this was in progress); `sidenav.component.html`
  had also changed on `main` (#144, moving the console dot). Merged `main` in and
  reconciled that one overlapping file by hand rather than rebasing, keeping both
  #144's change and this task's scroll-wrapper/widget insertion.
