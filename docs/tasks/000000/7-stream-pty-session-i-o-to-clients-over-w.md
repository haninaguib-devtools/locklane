# 7 — Stream PTY session I/O to clients over WebSocket
Issue: #7 · Part of: #1

## Asked
Add a network endpoint (e.g. WebSocket) to the `engine/` module so a browser client
can attach to a running worktree's PTY session and see/send live terminal I/O, and
can reattach after a dropped connection without killing the underlying session.

## Done when
- A client can attach to a running worktree's PTY session over the network and
  see/send live terminal I/O.
- Killing a client connection does not kill the underlying PTY session; a new
  connection can reattach and see output produced while it was disconnected.

## Explicitly not
- SQLite persistence of worktree/session state — delivered by #6.
- The Angular client — split to #3.

## Decisions made along the way
- Endpoint: `/ws/sessions/{worktreeId}[?dir=<path>]`. `dir` is required only the
  first time a worktree is seen; after that, the working directory is already known
  — in-memory if the session is still live, or from SQLite via #6's
  `SessionRegistry.lastKnownWorkingDirectory()` if this process restarted since (haninaguib, 2026-08-25).
- Extended `PtySession` (#5) with a `subscribe(OutputListener)` method: live output is
  now pushed to any current subscriber in addition to being buffered, which is how the
  WebSocket handler streams new output in real time rather than only replaying a
  snapshot at attach time. Chunks are defensively copied before being handed to
  listeners, since the drain loop reuses its read buffer on the next iteration
  (haninaguib, 2026-08-25).
- Closing a WebSocket connection only tears down that connection's subscription —
  `SessionRegistry`/`PtySession` are never touched from `afterConnectionClosed()` —
  so the underlying session and its process are unaffected by any one client
  disconnecting, which is the task's core done-when guarantee (haninaguib,
  2026-08-25).
- `WebSocketConfig` allows all origins (`setAllowedOrigins("*")`). There is no client
  yet (#3) and no authentication layer, and this task's scope is the transport only —
  flagged to the human as worth its own hardening task before this is ever reachable
  off `localhost` (an unauthenticated network endpoint that grants shell access is a
  real exposure once "reachable from anywhere" per ADR-002 is actually wired up)
  (haninaguib, 2026-08-25).
- Test approach: an integration test with a real embedded server
  (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) and a real WebSocket client
  (`StandardWebSocketClient`), rather than testing the handler in isolation — the
  done-when explicitly says "over the network," which an in-process test (like #5's)
  cannot demonstrate (haninaguib, 2026-08-25).
- Found and fixed a pre-existing gap surfaced by this task's tests, in scope as a
  fix to a file this task already touches: `engine/src/test/resources/application.yml`
  (added by #6) replaces `src/main/resources/application.yml` wholesale for tests —
  Boot resolves `classpath:/application.yml` to one file, and test-classes precedes
  classes on the test classpath — rather than merging with it. That silently dropped
  `spring.sql.init.mode: always` in every Spring-context test, so `schema.sql` never
  ran and `worktree_sessions` never existed in any test's database; #6's own tests
  never noticed because they build their schema directly via `TestSqliteDatabases`
  rather than going through Spring. This task's WebSocket integration test is the
  first to do a real `INSERT` through the Spring-wired `DataSource`, which is what
  surfaced it. Fixed by repeating the needed settings in the test file, with a comment
  explaining the shadowing behavior so it is not "cleaned up" by removing the
  duplication later (haninaguib, 2026-08-25).

## Deviations / notes
- none
