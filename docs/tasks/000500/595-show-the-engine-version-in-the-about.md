# 595 — Show the engine version in the About dialog regardless of when it opens
Issue: #595

## Asked
The About dialog (#575) says "version unknown" on every install, even though the engine
reports its version. The engine announces its version exactly once per events-channel
connection, as the `engineVersion` greeting, and the client pushes that greeting through
a plain non-replaying stream (`EventsService.events$`). Until #575 the sidebar footer
injected `RunningVersionService` at boot, so its subscription was live before the socket
connected; now the only consumer is the About dialog, which Angular constructs lazily on
the first click — after the greeting has already gone by. Fix this properly: the latest
greeting becomes *state* owned by the events channel (the one service the app
initializer creates at boot), so a consumer created at any later moment reads the
current value, and every reconnect's greeting replaces it. That covers an engine upgraded
while the client is running (the socket drops, reconnects, and the new engine's greeting
is the new state — the dialog shows the new version live, even if already open) and a
stale PWA bundle (the greeting protocol is unchanged and additive, so an older client
still learns the running engine's version). No eager-injection workaround: the fix must
make the late-subscriber class of bug impossible by construction, not depend on service
construction order.

## Done when
- `EventsService` exposes the latest `engineVersion` greeting as a readonly signal
  (`engineVersion`, `EngineVersionEvent | null`), set on every greeting (first connect
  and each reconnect), alongside the existing boot-stamp comparison which is unchanged.
- `RunningVersionService.version` is derived (`computed`) from that signal — its
  `release` field or `null` — with no subscription of its own, so it is correct no
  matter when it is first injected.
- `events.service.spec.ts` covers: the signal is `null` before any greeting; it holds
  the first greeting; a reconnect's greeting replaces it (including a changed `release`).
- `running-version.service.spec.ts` covers: a greeting that arrived *before* the service
  was first injected is still reported (the regression); a later greeting with a
  different `release` updates `version()`; a greeting without `release` (older engine)
  yields `null`.
- `about-dialog.component.spec.ts` gains a case where the dialog is opened while the
  version is unknown and then updates in place once a version arrives.
- Manually: on a running engine, open the account menu → About after the app has been
  open for a while; it shows `version <engine version>`; restart the engine on a
  different build while the app stays open; the dialog (re-opened or still open) shows
  the new version.
- `./mvnw -B test` and `./.t-workflow/scripts/consistency-check.sh` pass.

## Explicitly not
- No engine change: the greeting's shape and timing (`EventsWebSocketHandler`) stay as
  they are, so older PWA bundles keep working.
- No client-side version of its own in the dialog (the client bundle carries no version
  today — `client/package.json` is `0.0.0`); showing "client vs engine" would be a
  separate feature.
- No change to the reload-for-new-client prompt (`AppUpdateService`, #273).

## Decisions made along the way
- The greeting is kept as a signal on `EventsService` itself rather than replayed on
  `events$` (e.g. a `ReplaySubject`): `events$` is a stream of things that *happened*
  and consumers such as the console-attention dots must not receive a stale greeting as
  a fresh event; "what the engine last said about itself" is state, and the connection
  owner is the one object guaranteed to exist from boot (agent, 2026-09-02).

## Deviations / notes
- none
