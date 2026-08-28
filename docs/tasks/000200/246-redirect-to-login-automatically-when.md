# 246 — Redirect to login automatically when the server invalidates the session

Issue: #246

## Asked
Locklane's login sessions live only in the server's memory (Spring Security's default
in-memory `HttpSession` store). When the server restarts, every logged-in browser tab
silently loses its session: API calls start failing, but the page keeps showing whatever
was already on screen, with no sign that anything is wrong. The user only finds out by
manually reloading the page, at which point a one-time startup check notices the session
is gone and sends them to the login screen. We want that same "you're logged out, please
sign in again" redirect to happen automatically as soon as the lost session is detected,
with no manual reload required.

## Done when
- With a logged-in session open in the browser, restarting the backend process results
  in the app showing the login screen on its own, without the user reloading the page
  (verified manually: restart the local engine while logged in, confirm the login screen
  appears within a few seconds without touching the browser).
- A request that comes back `401 Unauthorized` from the API is treated the same way
  everywhere in the app — it always clears the logged-in state and shows the login
  screen, not just for the one startup check that exists today.
- Automated test coverage (Angular/Karma) demonstrates that a `401` response clears the
  logged-in state.

## Explicitly not
Making sessions survive a server restart (e.g. persisting sessions to the database
instead of keeping them in memory) is a separate, alternative fix and out of scope here
— this task is only about the client noticing and redirecting when a session is lost,
however it was lost.

## Decisions made along the way
- The 401 interceptor clears the logged-in state unconditionally, on every request
  including the login/2FA endpoints' own failure responses — those never set
  `isLoggedIn` true on a 401 anyway, so this is a no-op there, and it keeps the
  interceptor simple with no per-URL exceptions to maintain (hani, 2026-08-27).
- The lost-session recheck lives in `AuthService`'s constructor, subscribing to
  `EventsService#reconnected$` and calling the existing `checkSession()` — that stream
  already exists for exactly this "something may have changed while we were
  disconnected" purpose (#128), and it keeps `AuthService` self-contained rather than
  wiring the subscription in `app.config.ts` (hani, 2026-08-27).

## Deviations / notes
- `unauthorized.interceptor.ts`'s first draft called `inject(AuthService)` lazily
  inside the `catchError` callback. That works with a real backend (zone.js keeps the
  injection context alive across the async gap) but throws `NG0203` under
  `HttpTestingController`, whose `flush()` defers the error notification to a later,
  unwrapped tick — the throw was silently swallowed by the test's empty error handler,
  so the "clears the logged-in state" spec failed with the state unchanged. Fixed by
  calling `inject(AuthService)` synchronously at the top of the interceptor function,
  the documented-safe pattern, and storing the result for the callback to use (hani,
  2026-08-27).

