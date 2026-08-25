# 58 — Keep the user logged in across a page refresh
Issue: #58

## Asked
Refreshing the browser shows the login page even though the engine's session cookie is
still valid. The client's `AuthService` tracks login state only in memory and the engine
exposes no "who am I" endpoint, so a fresh page load cannot learn that the session is
still alive. Add a session-check endpoint on the engine (`GET /api/auth/me`, 200 when
authenticated, 401 otherwise) and have the client call it at startup to restore
`isLoggedIn` before the UI decides between login page and app.

## Done when
- With a valid session cookie, a full page reload lands the user back on the app (not
  the login page); with no/expired cookie it lands on the login page.
- `GET /api/auth/me` returns 401 unauthenticated and 200 when authenticated
  (integration-tested on the engine).
- Client unit tests cover the startup check restoring the logged-in signal.
- `./mvnw -B test` passes.

## Explicitly not
- No change to how sessions are stored or their lifetime; the existing cookie session
  (from #47/#54) stays as is.

## Decisions made along the way
- The startup check runs as a blocking app initializer (`provideAppInitializer`) so the
  first render already knows the answer — no login-page flicker on refresh (agent,
  2026-08-25).
- `/api/auth/me` returns the username in the body; the client only uses the status code
  today, but the payload costs nothing and the UI will want a name eventually (agent,
  2026-08-25).

## Deviations / notes
- none
