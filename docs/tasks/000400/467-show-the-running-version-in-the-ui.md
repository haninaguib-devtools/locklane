# 467 — Show the running version in the UI
Issue: #467 · Part of: #462

## Asked
The running locklane shows its own version somewhere in the UI. The jar knows its
version (`BuildProperties.getVersion()`, from `build-info.properties`) but displays it
nowhere — the only version a user ever sees is the "newer version available" banner,
with nothing to compare it against. Expose the running version to the client (the
events socket already sends an `engineVersion` stamp on connect, but that is the build
*time*, not the version — extend that payload or add the version alongside it) and
render it unobtrusively, e.g. in the sidenav footer or an about/settings spot.

## Done when
- The UI displays the running version, matching `BuildProperties.getVersion()`
  (e.g. `0.1.0-SNAPSHOT` on a dev build, `0.1.0` on a release build).
- A human judges the placement unobtrusive.

## Explicitly not
- No version comparison logic — the release banner (#287) already handles "newer
  available"; this only shows what is running.

## Decisions made along the way
- Extend the existing `engineVersion` greeting with a `release` field rather than
  adding a second connect message: one greeting, two facts — `version` stays the
  per-build time stamp (#273, staleness detection must not change shape mid-flight for
  older clients), `release` carries `BuildProperties.getVersion()` (agent, 2026-08-31).
- Display spot: the sidenav footer, pinned under the usage widget — always visible,
  muted, out of the way; mirrors the issue's own first suggestion (agent, 2026-08-31).
- Client wiring mirrors `ReleaseUpdateService`: a small `RunningVersionService` holds
  the version as a signal off `events$`, keeping message-interpretation state out of
  `EventsService`, same as #287 did (agent, 2026-08-31).
- The integration test's greeting assertion moves from a strict JSON-string regex to
  parsed-field assertions — `Map.of` gives no ordering guarantee, so a two-field
  payload makes byte-exact matching wrong, not just brittle (agent, 2026-08-31).

## Deviations / notes
- Driven child of #462 (ADR-004): branch based on `wip/462-integration`, draft PR
  opened against that integration branch, no auto-close phrase in the PR body — the
  aggregate PR to `main` closes #467 later.
- Dead end: the sidenav spec's first footer test used `TestBed.overrideProvider` after
  the shared `beforeEach` had already injected `HttpTestingController` — Angular
  refuses an override once the module is instantiated. The stub moved into the shared
  provider list with a per-test mutable value instead; first `./mvnw -B test` run
  failed on exactly that one spec (618/619 green), second run is the one that counts.
