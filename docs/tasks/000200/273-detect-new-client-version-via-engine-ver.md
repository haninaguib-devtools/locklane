# 273 — Detect new client version via engine version stamp on the events channel

Issue: #273

## Asked
When the engine is redeployed with a changed client bundle, a browser window already
running the PWA keeps running the old cached client indefinitely: the Angular service
worker pins a running tab to the version it booted with, and nothing in the app ever
checks for updates. Fix this by having the engine stamp its build version onto the
app-wide events channel: on every `/ws/events` connect, the engine sends a first message
carrying a version stamp unique to the build. The client records the stamp it saw at
boot; when a reconnect delivers a different stamp, the client asks the service worker to
fetch the new version and, once ready, tells the user a new version is available with a
one-click reload.

## Done when
- Connecting to `/ws/events` yields, before any other traffic, an event carrying a
  version stamp; two engine builds built from different commits or at different times
  carry different stamps.
- When the client's events channel reconnects and the received stamp differs from the
  one recorded at boot, the client calls `SwUpdate.checkForUpdate()`, and on
  `VERSION_READY` shows a visible reload affordance; activating it reloads into the new
  client bundle. Covered by client unit tests (stamp-change detection and prompt logic;
  the service worker itself may be faked).
- Manual check (human-judged): open the PWA, rebuild with a visible client change,
  restart the engine — the open window offers the reload within ~30s of the engine
  coming back, and reloading shows the new client.
- A stamp that is unchanged after reconnect produces no update check and no prompt.
- `./mvnw -B test` passes.

## Explicitly not
- No periodic `checkForUpdate()` polling timer — the reconnect stamp is the deploy
  signal.
- No API version negotiation or compatibility layer between client and engine versions.
- No forced auto-reload that discards what the user is doing; the user chooses when to
  reload.

## Decisions made along the way
- The version stamp is Spring Boot's `BuildProperties.getTime()` (an `Instant`, ISO-8601
  string), sourced from `spring-boot-maven-plugin`'s `build-info` goal — different for
  every Maven build, including two builds of the same commit at different times (hani,
  2026-08-28). The goal is bound to `process-resources` rather than its default
  `prepare-package` so `META-INF/build-info.properties` exists before the `test` phase
  runs, keeping `BuildProperties` available as a required dependency in
  `@SpringBootTest` tests too.
- The stamp is sent as a *targeted* message to the newly-connected session only (not
  broadcast to everyone), before that session is registered with `EventBroadcaster`, so
  it is guaranteed to be the first thing a connecting client sees.
- The client's "boot" stamp is recorded once, from the first `engineVersion` message it
  ever sees, and never updated afterwards — every later `engineVersion` message (each
  reconnect sends one) is compared against that original value, not the previous one.

## Deviations / notes
- none
