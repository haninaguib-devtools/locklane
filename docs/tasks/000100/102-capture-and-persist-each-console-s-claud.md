# 102 — Capture and persist each console's Claude/Codex resume session id
Issue: #102 · Part of: #101

## Asked
When a console starts, Claude/Codex prints a session id that can be used to resume that
exact conversation later (e.g. `claude --resume <id>`, `codex resume <id>`). The engine
only tracks that a worktree's PTY was attached (`WorktreeSessionRepository`) — it does
not capture or store this resume id, so once a console process is closed the
conversation is unreachable even though the CLI itself could resume it. Capture the
resume id from the console's output when a Claude or Codex session starts, and persist
it (tool, session id, worktree/issue, timestamp) so it survives a server restart, the
same way `worktree_sessions` does.

## Done when
- A new console session's resume id (Claude or Codex) is parsed from its output and
  saved to SQLite, associated with the worktree/issue it belongs to.
- The saved id survives an engine restart (durable table, not just in-memory).
- Existing PTY reattach behavior (`SessionRegistry`) is unchanged.
- `./mvnw -B test` passes.

## Explicitly not
- No UI for listing or reopening sessions — split to #99.

## Decisions made along the way
- Live-tested what the CLIs actually print (agent, 2026-08-27): claude 2.1.247 and
  codex 0.149.1 print **no** resume id at plain interactive startup — ids surface later,
  in output such as claude's `/status` screen, crash/exit hints (`claude --resume <id>`),
  and codex's `codex resume <id>` messages. So the scanner watches the session's whole
  output stream for its lifetime rather than only a startup banner; the issue's
  "when a session starts" premise does not hold verbatim for current CLI versions.
- Capture is keyed `(worktree_id, resume_id)` and rows are deliberately **not** deleted
  when a console is closed (`SessionRegistry.close`) — surviving the console's own
  teardown is the point of the table (#101 reopens conversations after the process is
  gone). Latest sighting of the same id refreshes `captured_at`.
- The tool ("claude"/"codex") is attributed by the matched pattern itself
  (`claude --resume <uuid>` vs `codex resume <uuid>`); a bare labeled form
  ("Session ID: <uuid>") is attributed only when the session's launch command already
  names the tool, and ignored otherwise — a shell console printing an unattributable
  uuid is noise, not a capture.
- Scanning runs on every session (not only `cmd=claude|codex` consoles): a user who
  launches `claude` by hand inside a shell console gets the same capture, and the
  explicit command patterns carry their own tool attribution.
- Scope note: the durable table required by the issue's done-when lives in a Flyway
  migration under `engine/src/main/resources/db/migration/` and tests under
  `engine/src/test/`, read as implied by the issue's Scope line (which names the two
  main source packages) since a durable table and a passing `./mvnw -B test` are
  explicit done-when items.

## Deviations / notes
- ANSI escape sequences (CSI/OSC) are stripped over a bounded rolling window of recent
  output before matching, so ids split across PTY reads or interleaved with TUI redraws
  still match; the window caps memory per session and TUI redraw loops are deduplicated
  per (tool, id).
