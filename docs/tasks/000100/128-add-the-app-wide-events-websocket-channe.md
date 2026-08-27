# 128 — Add the app-wide events WebSocket channel
Issue: #128 · Part of: #127

## Asked
Give the engine a way to push small notifications to every connected browser, so the
UI can react to changes without polling or manual refresh. One app-wide WebSocket
endpoint (`/ws/events`), separate from the per-session terminal sockets at
`/ws/sessions/*`, carrying JSON messages of the form `{"type": "...", ...}`.
Server-to-client only for now.

## Done when
- Engine exposes the events WebSocket endpoint and an injectable broadcaster; a test
  proves an event published on the broadcaster reaches a connected WebSocket client as
  JSON.
- Client service connects on app start, reconnects after a dropped connection, and
  surfaces events and reconnects as observables; covered by a unit test.
- `./mvnw -B test` and the client test suite pass.

## Explicitly not
- No event producers (issue changes, console attention) — those are #129 and #130.
- No client-to-server messages on this channel.

## Decisions made along the way
- none

## Deviations / notes
- none
