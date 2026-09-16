# 953 — Check for client update on login, not just WS reconnect
Issue: #953

## Asked
After an engine redeploy, the browser session is invalidated and the user has to log
back in. Today the client only checks for a new service-worker version when the
events WebSocket (`/ws/events`) reconnects and its `engineVersion` greeting shows a
different build stamp than the one seen at boot (`EventsService.versionChanged$` in
`client/src/app/services/events.service.ts`, consumed by `AppUpdateService` in
`client/src/app/services/app-update.service.ts`). That reconnect uses exponential
backoff (1s, 2s, 4s, 8s... capped at 30s, `events.service.ts` lines 206-207), so the
"reload for the new version" banner can lag 10-20 seconds behind the engine actually
being back up.

The user logging back in (`AuthService.login`/`verifyTwoFactor`/`completePasswordChange`
in `client/src/app/services/auth.service.ts`) is a faster, more reliable signal that
the engine is back, since it happens well before the socket's backoff schedule
necessarily catches up. Wire that signal into the same update-check path so the app
notices a new client version roughly as fast as the user notices they got logged out.

## Done when
- `AuthService` exposes an observable that fires once a session is freshly
  established (regular login, 2FA completion, or forced password-change completion).
- `AppUpdateService` also calls `SwUpdate.checkForUpdate()` on that signal, in addition
  to the existing `EventsService.versionChanged$` trigger.
- `client/src/app/services/app-update.service.spec.ts` covers the new trigger.
- `ng test` passes for the full client suite.

## Explicitly not
- Changing the WebSocket reconnect backoff schedule itself.
- Making the update check fully automatic/silent (the reload banner still requires a
  user click, unchanged).

## Decisions made along the way
- Added a `sessionEstablished$` `Subject<void>` on `AuthService`, fired from the same
  `tap` that flips `loggedIn` to `true` in `login`, `verifyTwoFactor`, and
  `completePasswordChange` — one signal covering all three "just became logged in"
  paths rather than three separate hooks in `AppUpdateService`.
- `AppUpdateService` merges `EventsService.versionChanged$` and
  `AuthService.sessionEstablished$` with `rxjs`'s `merge()` into the single existing
  `checkForUpdate()` subscription, so both triggers share the same `swUpdate.isEnabled`
  guard and error handling.

## Deviations / notes
- none

## Agents
- work: claude-code / claude-sonnet-5
