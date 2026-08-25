# 50 — Engine: Require authentication on the WebSocket session endpoint
Issue: #50 · Part of: #46

## Asked
The WebSocket session endpoint (`/ws/sessions/{worktreeId}`) has no authentication and
accepts connections from any origin (`WebSocketConfig.setAllowedOrigins("*")`). With
session-based login in place (#47) and per-user session ownership in place (#48), gate
this endpoint behind login, and restrict allowed origins to what the actual deployment
needs instead of `"*"`.

## Done when
- Connecting to `/ws/sessions/{worktreeId}` without an authenticated session is
  rejected (the handshake fails or the connection is closed immediately), rather than
  silently attaching.
- `WebSocketConfig` no longer allows all origins (`"*"`) — restricted to a configured
  list.
- A user can only attach to sessions scoped to them (builds on #48's per-user session
  scoping — already implemented; this task's job is closing the last gap, which was
  that an unauthenticated request could reach the handler at all).
- A human check: the chosen auth mechanism is judged adequate for a home-server
  deployment reachable from the public internet (not just from a trusted LAN).

## Explicitly not
The client's login UI (#49, already shipped) — this task is the server-side gate only.

## Decisions made along the way
- `/ws/sessions/**` added to `SecurityConfig`'s `authorizeHttpRequests` alongside the
  existing `/api/issues/*/worktrees` matcher — same session-cookie auth #47/#48 already
  built, no new mechanism. An unauthenticated handshake gets `401` (via the existing
  `HttpStatusEntryPoint` default entry point), which fails the WebSocket upgrade rather
  than completing it — satisfies "handshake fails... rather than silently attaching."
- Allowed origins moved to a new `locklane.security.allowed-origins` property
  (comma-list), defaulting to `http://localhost:4200,http://localhost:8080` — the
  Angular dev server (`ng serve`, via `proxy.conf.json`) and the bundled jar's own port
  respectively. Must be overridden for any real deployment.
- With authentication now mandatory upstream, `TerminalWebSocketHandler`'s existing
  null-principal branch (from #48, written for a still-open endpoint) is no longer
  reachable in practice — left in place as defense-in-depth rather than stripped, since
  removing it buys nothing and a defensive null check costs nothing.
- Updated stale `#50`-referencing comments across `schema.sql`,
  `IssueWorktreeService`, `WorktreeSessionRepository`, `SecurityConfig`, and
  `TerminalWebSocketHandler` that described "still open until #50" — this task is #50,
  so those comments would otherwise ship inaccurate the moment this merges.
- Test fallout: `TerminalWebSocketHandlerIntegrationTest`'s two scenarios connected
  anonymously — updated to log in first (same HTTP-login-then-WS-with-cookie pattern
  `WebSocketSessionOwnershipIntegrationTest` already established in #48).
  `WebSocketSessionOwnershipIntegrationTest`'s `anUnauthenticatedAttachStillWorksAndLeavesTheSessionUnclaimed`
  test asserted exactly the behavior this task removes — replaced with a test asserting
  the connection is now rejected. New tests cover origin restriction (a disallowed
  `Origin` header is rejected; an allowed one succeeds).

## Deviations / notes
- none
