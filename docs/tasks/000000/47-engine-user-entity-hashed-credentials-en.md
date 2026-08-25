# 47 — Engine: User entity, hashed credentials, encryption key file, session-based login
Issue: #47 · Part of: #46

## Asked
Add a User entity so the app supports multiple accounts, each with its own login.
Credentials stored in SQLite as salted password hashes (never plaintext). A separate
encryption key, used for any data the app encrypts at rest, is generated on first run
and stored outside the database (`<data-dir>/key`, alongside the existing
`<data-dir>/locklane.db`). Add Spring Security session-based login: a login endpoint
the client can call, session cookie thereafter. No client UI here.

## Done when
- A User entity/table exists with at least username and a salted password hash column.
- Passwords are hashed (BCrypt) before storage; no plaintext credential written to the
  database or logged.
- An encryption key is generated on first run if absent, stored at `<data-dir>/key`
  (owner-only file mode where the OS supports it), and loaded from there on subsequent
  starts.
- A login endpoint accepts username/password and establishes an authenticated session
  (cookie-based); a logout endpoint ends it.
- At least one user can be created (bootstrap/seed path acceptable).
- `./mvnw -B test` passes.

## Explicitly not
- Per-user scoping of existing data (issues, projects, sessions) — split to #48.
- The client's login UI — split to #49.
- Requiring auth on the WebSocket session endpoint — split to #50.
- Multi-user management (invite/create/delete via UI) beyond the bootstrap path.

## Decisions made along the way
- Login/logout wired via Spring Security's `formLogin`/`logout` support with a JSON
  success/failure handler (not the default HTML login page), since the client is an
  SPA calling an API. Endpoints: `POST /api/auth/login`, `POST /api/auth/logout`.
- This task does **not** lock down any existing endpoint — `authorizeHttpRequests()`
  stays `permitAll()` everywhere except the new auth endpoints themselves. Gating
  existing data/endpoints by authenticated user is #48's and #50's job; #47 only builds
  the login mechanism they'll consume.
- Bootstrap user: one user is created on startup if the `users` table is empty, from
  `locklane.security.bootstrap-username` / `bootstrap-password` (defaults documented in
  `application.yml`, must be overridden for any real deployment).
- Encryption key file: 256-bit random key, base64-encoded on disk at `<data-dir>/key`;
  generated once, `chmod 600` via `PosixFilePermissions` where the filesystem supports
  POSIX permissions (skipped, not failed, elsewhere e.g. Windows).
- CSRF protection is disabled on the security filter chain. Every endpoint besides the
  new login/logout is still `permitAll()`, so there's nothing state-changing-by-cookie
  to protect yet; a real CSRF strategy for the Angular SPA (e.g. a cookie-based
  double-submit token) is deferred to whichever of #48/#50 first gates a
  state-changing endpoint by session.

## Deviations / notes
- none
