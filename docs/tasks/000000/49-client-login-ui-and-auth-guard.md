# 49 — Client: Login UI and auth guard
Issue: #49 · Part of: #46

## Asked
With the engine's login endpoint and session-based auth in place (#47), the Angular
client needs a login screen and route guarding: an unauthenticated user is sent to
login instead of seeing the app, and a logout action is available once logged in.

## Done when
- A login page collects username/password and calls the engine's login endpoint.
- On successful login, the user is redirected to the app; on failure, an error is
  shown.
- Every app route is guarded — an unauthenticated visitor is redirected to login
  rather than seeing any app content, including on direct navigation to a deep link.
- A logout control ends the session and returns to the login page.
- Client tests pass.

## Explicitly not
- User management (creating/inviting/deleting users) — no UI for that here, only
  login against an existing account created via #47's bootstrap path.
- Real Angular Router route guards (`CanActivate`) — discussed directly with the human
  (2026-08-25): the client has no Angular Router yet at all (that's #31, a separate,
  unstarted task under a different initiative). "Every route is guarded" is
  implemented instead as a single whole-app gate in `AppComponent` — an
  unauthenticated visitor sees only the login screen, nothing else, since there is
  nothing else to navigate to yet either. #31 can layer real per-route guards on top
  of this when it lands.

## Decisions made along the way
- No client-side session-restore-on-reload: the engine has no "who am I" / session-check
  endpoint (#47 only added login/logout), and adding one is backend work outside this
  task's `client/src/app/` scope. The client defaults to "not authenticated" on every
  fresh load, even if the server-side session cookie is still valid — a real UX rough
  edge (forced re-login on browser refresh), documented rather than fixed, in the same
  spirit as the CSRF gap #47/#48 carried forward. Within one page load (no reload),
  the SPA behaves as one continuous logged-in session, since there's no Router to
  cause a real navigation/reload.
- Login submits as `application/x-www-form-urlencoded` (`username=&password=`), matching
  Spring Security's `formLogin` default parameter reader on the engine side — not JSON.
- Auth state lives in a small `AuthService` (a signal), not `AppComponent` itself, so
  a future logout control anywhere else in the tree (e.g. #32's header) can react to it
  too.

## Deviations / notes
- none
