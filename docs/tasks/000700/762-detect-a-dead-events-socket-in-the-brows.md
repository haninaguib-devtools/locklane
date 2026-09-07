# 762 — Detect a dead events socket in the browser and reconnect
Issue: #762 · Part of: #759

## Asked
A browser whose events connection has silently died must notice and reconnect on its
own, so the sidenav and header indicators catch up without a page reload. Today
`EventsService` (`client/src/app/services/events.service.ts`) reconnects only when
the browser delivers a `close` event, and its foreground check (`checkConnection`)
returns early whenever the socket reports OPEN — which a half-open socket does
indefinitely. The engine's heartbeat (#665) closes its own side after two missed
pongs, but the production deployment runs behind a Cloudflare tunnel and nginx, so
the browser's TCP connection and the engine's are separate legs: when the browser's
leg dies (laptop sleep, network change), the engine's close frame never reaches it,
the browser keeps an OPEN socket that will never deliver anything, and every
broadcast is lost until reload. Browsers do not expose protocol-level pings to
JavaScript, so the client currently has no liveness signal at all.

Give the client a signal it can see: the engine sends an application-level
`heartbeat` text message on the same `/ws/events` channel each heartbeat tick
(alongside the existing protocol ping, which stays for the server side), and the
client tracks the time of the last message of any kind. If more than two heartbeat
intervals pass with nothing received, or on returning to the foreground with the
last message older than that, the client closes the socket itself and reconnects
immediately, which fires the existing `reconnected$` full-reload path. The interval
is sent to the client in the `engineVersion` greeting so the two sides agree without
a hardcoded number.

## Done when
- `EventsWebSocketHandler` sends `{"type":"heartbeat"}` to every live session on each
  tick; the greeting carries `heartbeatIntervalMs`. Engine unit tests cover both.
- `EventsService` unit tests cover: (a) no message for more than two intervals causes
  a self-initiated close and reconnect, and `reconnected$` fires; (b)
  `checkConnection` on foreground with a stale last-message time reconnects even
  though the socket reports OPEN; (c) a socket receiving heartbeats on time is never
  closed; (d) the timer is throttling-safe — the check compares timestamps, never
  counts ticks, so a background tab that ran the timer late still reaches the right
  decision.
- `heartbeat` messages are filtered out before `events$` so no consumer sees them.
- `./mvnw -B test` passes.
- Manual check (human judgment): suspend the laptop for five minutes with the app
  open, resume, and the sidenav picks up an issue created during the suspension
  within one heartbeat interval of the window regaining focus.

## Explicitly not
- The terminal sockets' own reconnect logic (`terminal-session.ts`) — a separate
  channel with its own existing handling.
- Serializing WebSocket writes — sibling task #761.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The engine's `{"type":"heartbeat"}` goes out through `EventBroadcaster.broadcast`,
  called from `EventsWebSocketHandler.sendHeartbeats()` right after
  `TerminalHeartbeat.tick()`, rather than from inside the tick: the broadcaster
  already holds exactly the serialized wrappers #761 registers and already contains a
  per-session failure (log, drop, close, continue), so the message reaches every live
  session with the same containment as any broadcast, and `TerminalHeartbeat` — shared
  with the terminal sockets and outside this task's Scope — stays untouched. The tick
  runs first so a connection it just closed for missing pongs is not written to again
  on the same tick. (Claude, 2026-09-07)
- The client arms its liveness timer only once a greeting has named
  `heartbeatIntervalMs`, and keeps that interval across reconnects. No fallback
  constant: an engine that sends no interval (one older than this change) gets
  exactly the pre-#762 behaviour, and a reconnected socket is watched from the moment
  it opens rather than only after its own greeting. (Claude, 2026-09-07)
- Staleness is "more than two intervals since the last message of any kind", judged
  by `Date.now()` against a stored timestamp in both the timer path and the
  foreground path, mirroring the engine's own two-missed-pongs allowance. The timer
  runs once per interval; on time, that decides on the third silent tick, and a
  throttled or suspended tab decides on its first late firing. (Claude, 2026-09-07)
- A self-initiated reconnect detaches the old socket's handlers before closing it and
  every handler checks it still belongs to the current socket: on a dead connection
  the browser can take a long time to finish the close, and its eventual `close`
  event must not schedule a backoff reconnect on top of the replacement. (Claude,
  2026-09-07)
- `heartbeat` is consumed entirely inside `EventsService` (a module-private
  `HEARTBEAT_TYPE`), with no exported event type or guard: no consumer can ever see
  one, so exporting a guard would only invite a check that can never be true.
  (Claude, 2026-09-07)
- `EventsWebSocketHandlerIntegrationTest` — one of the handler's own tests, inside
  Scope — now counts non-heartbeat messages in `aClientOnlyReceivesEventsWhileConnected`:
  with the test profile's 1 s interval, a heartbeat landing between its two reads of
  the raw count would have failed it for reasons unrelated to what it asserts. It
  also pins `heartbeatIntervalMs` in the greeting against the injected configuration
  value. (Claude, 2026-09-07)

## Deviations / notes
- none
