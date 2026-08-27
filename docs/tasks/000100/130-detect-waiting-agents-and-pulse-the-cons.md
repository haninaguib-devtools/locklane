# 130 — Detect waiting agents and pulse the console indicator
Issue: #130 · Part of: #127

## Asked
When Claude Code or Codex running in a console session finishes or is waiting for the
user, the UI should show it: the blue per-issue console dot in the sidenav and the
header console indicator pulse / change color until the user focuses that console.
Detection happens in the engine, on two agent-agnostic signals — a BEL byte in the PTY
output, and output going quiet for a few seconds with no user input since — and is
published as a `consoleAttention` event on the app-wide events channel from #128.

## Done when
- An engine test proves: BEL in PTY output → `consoleAttention waiting` event;
  subsequent input to the session → `consoleAttention active` (cleared) event.
- Manual check: run `claude` in a locklane console, give it a prompt, switch to another
  issue; the sidenav dot for that issue pulses when Claude finishes, and stops when the
  console is focused/typed into.
- Sessions with no attention state look exactly as today.
- `./mvnw -B test` and the client test suite pass.

## Explicitly not
- No per-agent heuristics (parsing Claude/Codex prompt text or OSC sequences) — bell +
  quiescence only, per the issue's own non-goals.
- No desktop/system notifications — UI indication only.

## Decisions made along the way
- `PtySession` tracks `lastOutputAt`/`lastInputAt` as plain `System.currentTimeMillis()`
  fields rather than an injected `Clock` — the bell test needs a real subprocess anyway
  (existing tests in this package already use real waits for those), and the quiescence
  fallback exposes a package-private `checkQuiescence(long nowMs)` overload so its test
  can evaluate the threshold with a computed `now` instead of a real sleep (hani,
  2026-08-26).
- Focusing a session's terminal tab clears attention the same way real input does
  (`markFocused()` updates `lastInputAt` too, not just the attention flag) — without
  this, an idle-but-focused console would flip back to "waiting" the next time the
  quiescence poll ran, a few seconds after the user had just looked at it (hani,
  2026-08-26).
- Added a `'2'` focus frame to `TerminalWebSocketHandler`'s existing one-character
  message-type convention (`'0'` input, `'1'` resize) rather than a new endpoint or
  REST call, matching how the socket already carries input/resize (hani, 2026-08-26).
- `SessionRegistry` gained an `EventBroadcaster` dependency with the same test-only
  single-arg constructor pattern `ProjectGhResources` already uses (#129), so the
  existing `new SessionRegistry(repository)` call sites in `SessionRegistryReattachTest`
  and `WorktreeControllerTest` keep compiling unchanged (hani, 2026-08-26).
- `ConsoleAttentionEvent`/`isConsoleAttentionEvent` live in `events.service.ts` (next to
  `AppEvent`) rather than duplicated locally in each consumer, since — unlike
  `issuesChanged`, which only the sidenav reads — both the sidenav and the header
  console indicator react to this one (hani, 2026-08-26).

## Deviations / notes
- The manual check (run `claude` in a real console, watch the dot pulse and clear in a
  browser) was not performed in this session — it needs a live GitHub-backed project and
  an actual `claude` binary in a spawned PTY, which wasn't set up here. The engine test
  proves the exact bell → waiting → input → active transition end to end at the
  `PtySession` level, and client unit tests cover the event → dot/badge wiring on both
  ends, but nobody has watched it happen in a real browser yet.
