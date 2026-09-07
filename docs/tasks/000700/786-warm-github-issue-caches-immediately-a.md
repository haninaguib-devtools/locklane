# 786 — Warm GitHub issue caches immediately at startup
Issue: #786

## Asked
Populate every ready project's in-memory GitHub issue and pull-request cache as soon
as the engine starts, so a person signing back in immediately after an install or
update does not pay the cold-fetch delay in the sidenav.

## Done when
- Engine startup begins warming every ready project without waiting for the current
  30-second scheduled-refresh delay.
- The normal periodic 30-second refresh continues after the initial warm-up.
- Startup remains available while warming runs; slow or failed GitHub calls do not
  block engine readiness or prevent other projects from warming.
- Each project's successful or failed initial fetch follows the existing
  cache-retention, refresh-status, token-renewal, logging, and event-broadcast
  behavior.
- Automated tests demonstrate that warming starts immediately, covers every ready
  project, skips projects without a usable checkout, and isolates one project's
  failure from the others.

## Explicitly not
Persisting issue or pull-request data across engine restarts; changing sidenav
rendering behavior.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- `ProjectGhResources.refreshAll()` (the existing `@Scheduled` poll, #763) already
  covers every ready project, skips a project with no checkout (`CLONING`/`FAILED`,
  via the pre-existing `status() != READY` skip and `forProject`'s non-caching
  fallback), and isolates one project's failure from the rest (per-project `try`).
  Reusing it for the immediate warm-up — rather than adding a second startup-only code
  path — means all of that behavior applies to the first run for free, with nothing
  new to keep in sync. The only actual gap was the first run's own delay.
- Fixed by changing `@Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay =
  REFRESH_INTERVAL_MS)` to `initialDelay = 0`: Spring's scheduler then submits the
  first `refreshAll()` run immediately upon scheduling (i.e., right after the engine
  context comes up) instead of waiting out one full interval, while `fixedDelay`
  continues to space every later run 30 s after the previous one finishes — so the
  periodic cadence is unchanged past the first tick.
- Startup availability and per-tick isolation from other scheduled jobs were already
  structural: `@Scheduled` methods run on the dedicated `TaskScheduler` thread pool
  (sized to 4 in `application.yml`, #763) rather than the thread that boots the
  Spring context or serves HTTP requests, so submitting (and immediately running) this
  job earlier does not delay the engine becoming reachable, and a slow tick here still
  cannot starve the other six scheduled jobs sharing that pool.

## Deviations / notes
none
