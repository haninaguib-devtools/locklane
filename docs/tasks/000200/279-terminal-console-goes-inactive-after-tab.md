# 279 — Terminal console goes inactive after tab is backgrounded
Issue: #279

## Asked
When a browser tab is left in the background for a while, an open console's terminal
connection can go stale — the user returns to find the console unresponsive, and the
only way to bring it back is to navigate to another page in the app and back. Fix the
terminal connection so it recovers on its own when the tab regains focus, without
requiring page navigation.

## Done when
- Returning to a backgrounded tab whose terminal connection died reconnects the
  console automatically, without navigating away from and back to the page — verified
  manually.
- `TerminalSession`/`TerminalComponent` reconnect after an unexpected socket close,
  instead of the current no-op `onClose` handler.
- The client detects tab re-foregrounding (`visibilitychange` and/or `focus`) and
  checks/repairs the terminal connection at that point.
- A stale/half-open connection is detectable in a bounded time — some form of
  keepalive (ping/pong or equivalent) on `/ws/sessions/*`, client- and/or server-side.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not
- Refactoring `EventsService`'s reconnect logic into a shared abstraction used by both
  channels — reuse the pattern, not necessarily the code.
- Changing the PTY reattach/resize protocol itself (#275's `pendingResize` mechanism).
- Any reconnect/keepalive behavior for the events channel — it already has one.

## Decisions made along the way
- `TerminalSession` owns its own reconnect (exponential backoff, same shape as
  `EventsService`'s) and exposes `checkConnection()` for `TerminalComponent` to call
  on `visibilitychange`/`focus`, rather than pushing reconnect policy up into the
  component — mirrors how `EventsService` is self-contained.
- The keepalive is server-side only: the engine's new `TerminalHeartbeat` pings every
  live `/ws/sessions/*` connection on a schedule and closes one that misses two pongs
  in a row. A browser answers a server-sent WebSocket-protocol ping with a pong
  automatically, with no client code involved, so this needed no new app-level
  message tag on the terminal wire protocol. `TerminalWebSocketHandler` became a
  Spring `@Component` (previously constructed by hand in `WebSocketConfig`) so its
  heartbeat could use `@Scheduled`; the heartbeat interval is configurable
  (`locklane.terminal.heartbeat-interval-ms`) so tests run it on a much shorter cycle
  than production's 20s default.
- Reused the existing `Clock` bean (`UsageConfig#usageClock`) for the heartbeat's
  timestamps rather than adding a second one, following that file's existing
  Clock-for-testability pattern.

## Deviations / notes
- none
