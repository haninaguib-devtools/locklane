# 776 — Open IDE shows a 502, then opens the wrong workspace

Issue: #776

## Asked
Clicking a console tab's "Open IDE" should land the browser in code-server with that
console's own worktree open, on the first click — not a 502 while code-server is still
starting, and not another console's remembered workspace after a refresh.

## Done when
- `CodeServerService.start` returns only once code-server accepts TCP connections on
  its allocated loopback port, polling with a bounded timeout (10–15s). A process that
  never listens, or exits first, is stopped and untracked and `start` fails.
- The `open-ide` response URL carries the console's worktree as a URL-encoded `folder`
  query parameter.
- The code-server command line includes `--ignore-last-opened`.
- Manual check on this machine (human judgment): with a stale remembered folder,
  "Open IDE" opens the right worktree at the first click, no 502, no wrong-workspace
  dialog.
- `./mvnw -B test` passes.

## Explicitly not
- Giving each code-server process its own user data directory.
- Any change to the client.
- Changing the proxy's 502 handling for a code-server that dies later in its life.

## Origin
none

## Verification
none

## Feedback
none

## Decisions made along the way
- none

## Deviations / notes
- The issue's Scope line names `docs/tasks/.../codeserver/` as the test directory, but
  the Done-when explicitly requires `ConsolesControllerTest` (which lives under
  `engine/src/test/java/dev/locklane/engine/persistence/`) to assert on the new
  `folder` query parameter. Treated as in scope: it is the existing test file for
  `ConsolesController.java`, one of the two files the Scope line names directly, and
  the Done-when is unambiguous about it.
