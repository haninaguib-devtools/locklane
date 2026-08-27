# 167 — Fix recurring flaky EventsWebSocketHandlerIntegrationTest.aClientOnlyReceivesEventsWhileConnected
Issue: #167

## Asked
`EventsWebSocketHandlerIntegrationTest.aClientOnlyReceivesEventsWhileConnected` (engine
module) is intermittently failing on CI again, after #154/#156 already tried to fix this
exact test — three separate CI runs on unrelated branches, failing two different ways
(`IllegalStateException: Message will not be sent because the WebSocket session has been
closed` from `EventBroadcaster.send`, and the opposite: the broadcast landing and the
client receiving `{"type":"no.subscribers.left"}`). Both are the same race: #156 made
the test wait on the *client's own* `afterConnectionClosed` callback before
broadcasting, but the low-level WebSocket container's close handshake (which fires that
callback) can complete before the application-level unregister side effect on the
server has finished running. The test must synchronize on a signal that genuinely
reflects server-side unregistration, not a client-observable proxy for it.

## Done when
- `aClientOnlyReceivesEventsWhileConnected` (and
  `anEventPublishedOnTheBroadcasterReachesAConnectedClientAsJson`) pass repeatedly, e.g.
  `./mvnw -B test -pl engine -Dtest=EventsWebSocketHandlerIntegrationTest` green across
  many consecutive runs (locally and on CI) — no widened timeouts or retries papering
  over the race.
- No behavior change to the production WebSocket channel itself.

## Explicitly not
- No behavior change to the production WebSocket channel's client-facing semantics.

## Decisions made along the way
- The only state that genuinely reflects server-side unregistration is the
  broadcaster's own session registry — it is what `broadcast` iterates, so it is the
  state that actually gates delivery. Exposed it as a package-private
  `EventBroadcaster.registeredSessionCount()` (the issue's scope explicitly allows a
  main-source change "if truly required to expose a genuine unregistration signal");
  no production behavior changes, it only reads `sessions.size()`. (agent, 2026-08-27)
- `aClientOnlyReceivesEventsWhileConnected` now waits for `registeredSessionCount() == 1`
  after connecting *before* closing, then for `== 0` before broadcasting. The first wait
  matters: without it, a count of 0 could mean "the server has not registered this
  session yet", and the broadcast could then race with a late registration — the same
  flake in a new spot. Waiting 1 → 0 proves this session completed its full
  register/unregister lifecycle. (agent, 2026-08-27)
- `anEventPublishedOnTheBroadcasterReachesAConnectedClientAsJson` ends by waiting for
  the registry to drain after its `session.close()`, so its session can never linger
  into the other test's count-based waits. Its existing broadcast-retry loop (#154) is
  unchanged. (agent, 2026-08-27)
- Removed the `RecordingHandler.closed` latch and its `afterConnectionClosed` override:
  the client-side close callback was #156's proxy signal, shown by this issue to be
  unreliable, and nothing else used it. (agent, 2026-08-27)

## Further findings (not acted on)
- `EventBroadcaster.send` catches only `IOException`, but the CI failures show the
  container throwing `IllegalStateException` ("Message will not be sent because the
  WebSocket session has been closed") from `sendMessage` when a session closes
  concurrently with a broadcast. In production, a producer broadcasting during a client
  disconnect could propagate that exception to its caller. Out of scope here (the issue
  forbids production behavior changes beyond exposing the signal); proposed as its own
  issue in the closing report.

## Deviations / notes
- none
