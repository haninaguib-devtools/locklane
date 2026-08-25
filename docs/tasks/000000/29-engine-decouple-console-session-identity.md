# 29 — Engine: decouple console session identity from worktree, support agent/location choice on attach
Issue: #29 · Part of: #28

## Asked
`SessionRegistry` keyed one PTY session per worktree id and always launched the
default `$SHELL`. To let someone open several consoles for the same issue —
including more than one on the main checkout — and choose what runs in each,
session identity needs to be its own thing, separate from the worktree/working
directory it happens to run in, and the launch command needs to be chosen per
session instead of fixed.

## Done when
- A session has its own id, independent of worktree id; multiple sessions can
  point at the same working directory.
- `attach()` (or its HTTP/WS entry point) accepts a launch command (`claude`,
  `codex`, or a plain shell) instead of always using `defaultShellCommand()`.
- Opening a console against the main checkout does not require a worktree to
  exist first — `WorktreeCreationService`'s `git worktree add` only runs when the
  caller explicitly asked for a worktree location.
- Existing reconnect/output-buffer/replay behavior (`PtySession`,
  `TerminalWebSocketHandler`) keeps working per session.
- `./mvnw -B test` passes.

## Explicitly not
- No client (Angular) UI for picking an agent or a location — the issue's scope
  is the engine only. A follow-up client task would consume the `worktree`
  request param and the WS `cmd` param this change adds.
- No persistence of a session's launch command across a server restart —
  `PtySession`'s process (and whatever it was launched with) doesn't survive a
  restart either way, so there's nothing to restore.

## Decisions made along the way
- Kept `WorktreeSessionRepository`/`WorktreeSessionRecord`/`IssueWorktreeService`
  and `schema.sql` unchanged: their "worktree id" was already just an opaque
  session-identifying string (per `IssueWorktreeService`'s own comment — "a
  worktree id is just whatever string a client chose"), so the decoupling the
  issue asks for is real without renaming that layer. Renaming there would have
  been a much larger diff for no behavioral gain.
- Renamed `PtySession`/`SessionRegistry`/`PtySessionStartException`/
  `PtySessionIoException`'s `worktreeId` to `sessionId` (they're in the issue's
  declared scope, `engine/.../pty/`) to make the decoupling explicit where it's
  actually implemented.
- WS `?cmd=` values: `claude` and `codex` map to running that program directly;
  `shell` or absent falls back to the default shell — matches the issue's three
  named options without inventing a bigger command-selection scheme.
- Main-checkout sessions get a freshly minted id every call
  (`<issue>-main-<random8>`), rather than being reused like worktree sessions
  are — this is what lets someone open more than one console on the main
  checkout, which the issue's done-when calls out by name.

## Deviations / notes
- Touched `engine/src/main/java/dev/locklane/engine/ws/TerminalWebSocketHandler.java`,
  which is outside the issue's literal Scope line (`.../pty/`,
  `WorktreeCreationService.java`, `WorktreeController.java`). The issue's own
  done-when names "`attach()` (or its HTTP/WS entry point)" as where the launch
  command is accepted, and `TerminalWebSocketHandler` is that WS entry point and
  `SessionRegistry`'s only caller — the change does not compile or make the
  launch-command choice reachable by a client without it. Treated as necessary
  plumbing for the scoped change rather than a drive-by.
- `WorktreeCreationService`'s existing "reuse an existing session for this issue"
  path (`issueWorktreeService.worktreeIdsForIssue`) now filters out
  `<issue>-main-*` ids before picking a worktree candidate, since those ids match
  the issue-number prefix but were never a worktree path. Without the filter, a
  main-checkout session attached before a worktree request for the same issue
  could be handed back as if it were the worktree's working directory.
