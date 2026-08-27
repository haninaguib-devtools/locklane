# 173 — Reconnecting to a console after an engine restart should resume the conversation
Issue: #173

## Asked
When the engine restarts, a browser reconnecting to an existing console session id
gets a brand-new, blank Claude/Codex process in the same slot — the session id and
its place in the UI survive the restart, but the conversation does not. Reconnecting
should pick the conversation back up using the resume id already captured for that
session by #102. This is the automatic reattach-by-session-id path, distinct from
#103's manual "reopen" button, which deliberately starts a second session.

## Done when
- After an engine restart, a client reattaching to a session id that has a captured
  Claude/Codex resume id (`ConsoleResumeSessionRepository`) launches that tool with
  `--resume <id>` / `resume <id>` instead of a bare `claude`/`codex` invocation.
- A session with no captured resume id (never ran claude/codex, or no id was
  captured yet) still falls back to today's behavior — a plain launch, no error.
- Reattaching while the original process is still alive (no restart happened) is
  unaffected — it reaches the live process as it does today, never re-launching.
- `engine` build and existing tests pass.

## Explicitly not
- Any change to the Overview tab's manual "reopen" flow (#103) — that already works
  and stays a separate, new-session-id path.

## Decisions made along the way
- The fallback lives in `TerminalWebSocketHandler` (which already composes resume
  commands for #103) and consults the repository through a new
  `SessionRegistry.latestResumeId(sessionId, tool)` — the registry already owns the
  repository handle, and the existing `findByWorktree` read (oldest-first) suffices,
  so `ConsoleResumeSessionRepository` itself is untouched (agent, 2026-08-27).
- The fallback only fires when no live `PtySession` exists for the id, the client
  passed no explicit `resume`, and `cmd` is `claude`/`codex`; the latest captured id
  *for that same tool* is used, so a codex id never feeds `claude --resume`
  (agent, 2026-08-27).

## Deviations / notes
- none
