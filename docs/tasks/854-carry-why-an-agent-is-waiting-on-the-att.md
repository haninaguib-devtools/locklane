# 854 — Carry why an agent is waiting on the attention event: bell or quiet
Issue: #854 · Part of: #853

## Asked
Make the attention event say why a session is waiting. The engine marks a PTY session as waiting on two signals (#130): a bell byte in its output, and output gone quiet for `PtySession.QUIESCENCE_THRESHOLD_MS` with no input since. Both reach the client as the same `consoleAttention` event with `state: "waiting"`, so a consumer cannot tell a deliberate bell from the quiet-output fallback. The upcoming browser notification must fire only for bells, while the dot keeps reacting to both, so the event needs a `reason` field: `"bell"` or `"quiet"`. A session already waiting for quiet that then rings the bell must re-emit with the stronger reason, even though its state has not changed; a bell followed by quiet keeps `bell`. The connect-time snapshot (#790) carries the same field, so a page opened after the bell rang sees the same reason a live listener would. On the client, the attention store exposes the reason per session next to the existing `isWaiting`, and every existing consumer behaves exactly as before.

## Done when
- `consoleAttention` events with `state: "waiting"` carry `reason: "bell"` or `reason: "quiet"`; `state: "active"` events carry no reason. The wire shape is a compatible addition: every existing client reader of the event still passes its spec unchanged.
- An engine test proves: BEL in output → waiting with reason `bell`; quiet past the threshold with no input → waiting with reason `quiet`; quiet then BEL → a second event, still waiting, reason now `bell`; BEL then quiet → no further event; input → active.
- The #790 snapshot sent on connect carries each waiting session's current reason.
- `AttentionStore` exposes a signal-based `reason(sessionId)` returning `'bell' | 'quiet' | null`, and `isWaiting` is unchanged; a spec covers the quiet-to-bell upgrade.
- `./mvnw -B test` and the client test suite pass.

## Explicitly not
- Changing when a session becomes waiting or active, or the quiet threshold.
- Any change to the sidenav, header badge or tab dot; they read `isWaiting` and stay as they are.

## Decisions made along the way
- none

## Deviations / notes
- none
