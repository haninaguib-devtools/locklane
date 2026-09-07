# 761 — Serialize WebSocket writes in EventBroadcaster and the heartbeats
Issue: #761 · Part of: #759

## Asked
Notifications the engine pushes to browsers must never be lost because two threads
wrote to the same WebSocket at once. Today `EventBroadcaster` calls
`session.sendMessage` on raw Tomcat sessions from the scheduler thread
(`issuesChanged`, `githubRefreshStatus`), HTTP threads (`consolesChanged`,
`projectStatus`, forced-refresh `issuesChanged`), and every PTY drain thread
(`consoleAttention`), and `TerminalHeartbeat` pings those same sessions on the
scheduler thread. Tomcat sessions are not thread-safe for concurrent sends: a
collision throws `IllegalStateException` ("The remote endpoint was in state
[TEXT_PARTIAL_WRITING]…"), which `send` does not catch. Observed in production on
2026-09-07 at 01:54, closing an unrelated terminal session. Worse than the log line:
the broadcast loop aborts, so every session later in the set misses that message, and
when the losing thread is the scheduled poll its `issuesChanged` is lost permanently —
the cache has already moved, so the next poll compares equal and never re-announces.

Make every write to a session serialized and every per-session failure contained:
wrap each registered session in Spring's `ConcurrentWebSocketSessionDecorator` (with a
send-time limit and buffer cap so one stuck client cannot block the rest), and in the
broadcast loop catch any `RuntimeException` from one session, log it, drop or close
that session, and continue with the others. Apply the same containment to both
heartbeat tickers, which write to the same sessions. `EventsWebSocketHandler` and
`TerminalWebSocketHandler` register the decorated session so all writers, pings
included, go through one lock.

## Done when
- `EventBroadcaster.broadcast` cannot abort mid-loop: a unit test registers three fake
  sessions where the second throws `IllegalStateException` on send and asserts the
  first and third still receive the message and the second is unregistered or closed.
- A unit test drives concurrent `broadcast` calls from several threads against a
  session that asserts single-threaded access (or a real Tomcat session in the existing
  WebSocket integration test) without any `IllegalStateException`.
- Heartbeat ticks tolerate a session that throws a `RuntimeException` on ping: the tick
  continues to the remaining sessions (unit test).
- `./mvnw -B test` passes.
- `grep -c "invalid state for called method" ~/.locklane/locklane.log` does not grow
  after deploying (human observation over a day of normal console use).

## Explicitly not
- Changing what events exist or when they are sent.
- Client-side reconnect behaviour — sibling task under #759.
- An application-level heartbeat message on `/ws/events` — sibling task #762.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The one-lock wrapper is a static factory on `TerminalHeartbeat`
  (`TerminalHeartbeat.serialized(session)`), not a new class: the heartbeat is the
  writer every live connection on both endpoints has in common, both handlers already
  own one, and keeping it there keeps the diff inside the four files the issue's Scope
  names. Limits: 10 s per send, 1 MiB of queued output — Spring's usual send-time
  default, and a buffer sized for a terminal's output bursts rather than the events
  channel's small JSON. (Claude, 2026-09-07)
- `EventBroadcaster`'s registry is keyed by session id rather than by instance: the
  handler registers the wrapper but Spring hands the raw session back to
  `afterConnectionClosed`, and an id-keyed map lets `unregister(raw)` find the wrapper
  the same way `TerminalHeartbeat` already does. (Claude, 2026-09-07)
- `TerminalWebSocketHandler.forward` contains a `RuntimeException` too, closing the one
  connection: `PtySession`'s drain loop treats a listener's `RuntimeException` as fatal
  for the whole session, so a wrapper refusing a write (a stuck client past its limit)
  would otherwise stop output for every client attached to that PTY. (Claude,
  2026-09-07)
- `EventsWebSocketHandlerTest`'s three greeting assertions now match the serializing
  wrapper around the fake session (unwrapped back to it) instead of the raw instance —
  the greeting is deliberately sent through the same wrapper every later write uses.
  (Claude, 2026-09-07)

## Deviations / notes
- none
