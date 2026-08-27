# 154 — Fix flaky EventsWebSocketHandlerIntegrationTest
Issue: #154 · Part of: #153

## Asked
Two tests in `EventsWebSocketHandlerIntegrationTest` (engine module, added with the
app-wide events WebSocket channel in #150) fail intermittently on CI, blocking the
Release workflow from publishing. Observed failures: `aClientOnlyReceivesEventsWhileConnected`
found a leftover `{"type":"no.subscribers.left"}` message where it expected none, and
`anEventPublishedOnTheBroadcasterReachesAConnectedClientAsJson` timed out waiting for a
message. Make these tests deterministic by synchronizing on connection/subscription
state instead of sleeps/fixed timeouts.

## Done when
- Both tests pass repeatedly, e.g. `./mvnw -B test -pl engine -Dtest=EventsWebSocketHandlerIntegrationTest`
  green across many consecutive runs (locally and on CI).
- No fix by widening timeouts alone; the tests wait on an observable condition.

## Explicitly not
- No behavior change to the production WebSocket channel itself.

## Decisions made along the way
- Root cause 1 (`anEventPublishedOnTheBroadcasterReachesAConnectedClientAsJson`): the
  client-side connect future completing does not guarantee the server has already
  registered the session with `EventBroadcaster` — registration is a server-side side
  effect with no signal back to the client. Fix: retry the broadcast (a no-op when
  nobody is registered yet) inside the wait loop until the message actually lands,
  instead of broadcasting once and passively waiting. Since a retry can occasionally
  land more than one identical copy of the message before the loop notices the first
  arrival, the assertion checks every received message equals the expected JSON
  (`allMatch`) rather than asserting exactly one.
- Root cause 2 (`aClientOnlyReceivesEventsWhileConnected`): `session.isOpen()` flips on
  the client as soon as it initiates the close — before the server has necessarily
  processed the close frame and called `EventBroadcaster.unregister`. Broadcasting right
  after `session.isOpen() == false` could still reach a session the server hadn't
  unregistered yet. Fix: wait on the client handler's own `afterConnectionClosed`
  callback instead, which — per the WebSocket closing handshake — only fires after the
  server has echoed its own close frame back, which the server can only send after
  running its own `afterConnectionClosed` (unregistering) first. This makes the wait a
  proxy for the real server-side unregistration.
- Both fixes stayed inside `engine/src/test` (the issue's scope); no change to
  `EventBroadcaster` or `EventsWebSocketHandler`.

## Deviations / notes
- none
