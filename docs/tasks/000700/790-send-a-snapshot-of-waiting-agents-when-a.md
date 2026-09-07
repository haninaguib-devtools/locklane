# 790 — Send a snapshot of waiting agents when a client connects to the events channel
Issue: #790 · Part of: #789

## Asked
An agent that is waiting for the user should show as waiting in every indicator, even
in a browser page that was opened, reloaded or reconnected after the agent stopped.
Today the engine only pushes a `consoleAttention` event over the app-wide events
channel (`/ws/events`) at the moment a session's attention state changes (#130), so a
client that connects later shows a waiting agent as calm until it rings the bell again.
The fix is a snapshot on connect: when a client connects to the events channel, the
engine sends it one `consoleAttention` event with `state: "waiting"` for each live
session currently waiting, in the exact shape the live broadcast already uses, so every
existing and future consumer catches up with no new message type and no change to any
REST list endpoint. Because the client reconnects the same channel after a network drop
or an engine restart, the same snapshot also repairs state lost across a reconnect.

## Done when
- `PtySession` exposes its current `AttentionState` through a public accessor (it is
  only held in a private `AtomicReference` today).
- `SessionRegistry` exposes the ids of the live sessions currently in
  `AttentionState.WAITING`.
- `EventsWebSocketHandler.afterConnectionEstablished` sends the new connection one
  `consoleAttention` message (`{"sessionId": <id>, "state": "waiting"}`, via
  `EventBroadcaster.sendTo`) per waiting session, after the existing `engineVersion`
  message. A connection is registered for broadcasts before the snapshot is taken, so a
  state change racing the connect is never lost between the two. No message is sent for
  sessions that are active.
- A unit test in `engine/src/test/java/dev/locklane/engine/ws/EventsWebSocketHandlerTest.java`
  asserts that a connecting client receives exactly one waiting event per waiting
  session and none for an active one, and a `SessionRegistry` test covers the new query.
- The audience is the same as today's broadcast: the live `consoleAttention` event
  already goes to every connected client regardless of project, so the snapshot does
  too; this task does not introduce per-project filtering on the events channel.
- `./mvnw -B test` passes.

## Explicitly not
- Adding attention state to `GET /api/projects/{projectId}/consoles` or
  `GET /api/projects/{projectId}/console/sessions`; the events channel is where every
  consumer already listens, so the snapshot goes there instead.
- Any client change; the client half is its sibling task under the same initiative.
- Changing when a session counts as waiting, or the heartbeat and reconnect mechanics
  of `/ws/events`.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- The handler learns the waiting ids through a `Supplier<Collection<String>>` rather
  than holding `SessionRegistry` directly, the same shape its existing `newerRelease`
  supplier already has: the `@Autowired` constructor passes
  `sessionRegistry::waitingSessionIds`, and the test-only constructors take a fake
  supplier (defaulting to none waiting) so the existing unit tests keep constructing
  the handler without a registry. (agent, 2026-09-07)
- The snapshot is sent after `register` and after `heartbeat.track`, in that order —
  registration first is what the issue requires; tracking before the snapshot just
  keeps the connection's liveness bookkeeping in one place ahead of any further write.
  (agent, 2026-09-07)

## Deviations / notes
- `EventsWebSocketHandlerIntegrationTest.aClientOnlyReceivesEventsWhileConnected`
  failed on the first full run (expected 3 messages, got 10): it took its
  "messages before close" count as soon as the connection was registered, which used
  to be the end of the connect-time traffic. The snapshot this task adds is sent
  *after* registration by design, and in the shared Spring test context it replays a
  line for every session earlier test classes left running and now quiescent. The
  test now also waits for the snapshot line of every currently-waiting session to
  land before taking its count; its assertion is unchanged. The file is under the
  Scope line's `engine/src/test/java/dev/locklane/engine/ws/` and is this handler's
  own test. (agent, 2026-09-07)
- Registering before the snapshot means a change that races the connect is delivered
  twice at worst (once by the live broadcast, once by the snapshot), never lost. The
  reverse interleaving — the snapshot reads a session as waiting, the session flips to
  active and broadcasts, then the snapshot's `waiting` line lands after that broadcast
  — is still possible in a window of microseconds and would leave that one client
  reading the session as waiting until its next change. Closing it needs a sequence
  number or a lock shared with the broadcaster, which the issue does not ask for; noted
  here rather than built.
