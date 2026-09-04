# 665 — Keep the events WebSocket alive so the consoles widget stops going stale
Issue: #665

## Asked
The header consoles widget (`ConsoleIndicatorComponent`) sometimes does not notice that
a console was opened or closed until the page is reloaded. The widget learns about
changes through the app-wide events WebSocket (`/ws/events`, the `consolesChanged`
broadcast), and that socket has no keepalive: unlike the per-console terminal sockets
(which got a server-side ping and missed-pong close in #279), a `/ws/events` connection
that a proxy idle timeout, laptop sleep, or a throttled background tab silently drops
never produces a `close` event in the browser, so `EventsService` neither reconnects nor
re-fetches, and every later `consolesChanged` (and `consoleAttention`, `issuesChanged`,
`githubRefreshStatus`, `releaseAvailable`) is lost until a manual reload. Make the events
channel detect a dead connection within a bounded time and recover on its own, so the
widget (and everything else on that channel) catches up without a reload.

Background on why the widget depends on the broadcast so completely: the engine records
a console as open only when its terminal socket attaches (`SessionRegistry.attach` →
`recordAttach`), which happens after the tab mounts, while the client notifies the
widget (`ConsolesService.notifyOpened`) as soon as the HTTP start request returns — so
the widget's own local refetch runs before the record exists and sees the old list. The
engine's `consolesChanged` broadcast right after the attach is what corrects it. This
race is masked whenever the events socket is healthy and is not itself in scope here
(see Explicitly not).

## Done when
- The engine pings every live `/ws/events` session on a fixed schedule and closes any
  that misses `MISSED_PONGS_BEFORE_CLOSE` pongs in a row, reusing `TerminalHeartbeat`
  (or a shared extraction of it) rather than a second copy; the interval is configurable
  next to `locklane.terminal.heartbeat-interval-ms` in `application.yml`.
- A `/ws/events` connection whose peer stops answering pongs is closed server-side
  within `2 × interval` — covered by a unit test driven through a fake
  `WebSocketSession` and a controllable `Clock`, the same way `TerminalHeartbeatTest`
  works today.
- `EventsService` re-checks its connection when the document becomes visible or the
  window regains focus (mirroring `TerminalSession.checkConnection()` /
  `TerminalComponent.checkConnectionOnForeground`), and opens a new socket immediately
  if the current one is not `OPEN` — covered by a client spec.
- After such a recovery `EventsService.reconnected$` fires, so
  `ConsolesService.onOpened`/`onClosed` trigger the widget's existing catch-up refetch
  (no change to `ConsoleIndicatorComponent` needed for that).
- `./mvnw -B test` passes.
- Human-judged: with the engine running, open a console from one browser window and
  close it from another (or after the laptop slept) — the header widget's count follows
  within seconds, without a reload.

## Explicitly not
- Fixing the client-side race where `ConsolesService.notifyOpened()` fires before the
  engine's attach has recorded the console (the widget's local refetch is early by
  design and the broadcast corrects it). Harmless once the events channel is reliable;
  left as is.
- Any change to what `consolesChanged` carries or when the engine broadcasts it.
- Rename events over the channel (#456's declared boundary stands).

## Decisions made along the way
- none

## Deviations / notes
- none
