# 103 — Overview tab: list and reopen past console sessions
Issue: #103 · Part of: #101

## Asked
An issue's Overview tab (#96) should list its past Claude/Codex console conversations
and let the user reopen any of them — starting a new console that resumes that exact
conversation (`claude --resume <id>` / `codex resume <id>`) instead of a blank one,
using the resume ids captured and persisted by #102.

## Done when
- The Overview tab shows a list of past console sessions for the issue (tool, started
  time, at minimum).
- Clicking "reopen" on a session starts a new console running `claude --resume <id>` or
  `codex resume <id>` as appropriate, using the resume id saved by #102.
- `client/src/app` build and existing tests pass.

## Explicitly not
- No editing/deleting saved sessions from the list.

## Decisions made along the way
- Scope note (same reading as #102's record): the issue's Scope line names only the
  client component paths, but the done-when — a session list served from #102's SQLite
  table, and a reopened console actually running the resume command — requires engine
  support that does not exist yet (no endpoint exposes `console_resume_sessions`, and
  the terminal WebSocket's `cmd` parameter launches single-word commands only). Engine
  changes are read as implied by the done-when: a list/reopen endpoint, and resume
  support in the terminal launch path. On the client, the same reading covers the
  wiring the named components need: the issues service and model (the new endpoints'
  client side), the terminal component/session (carrying the resume parameter to the
  WebSocket), and a one-line fixture update in `app.component.spec.ts` so existing
  tests pass with the Overview tab's new fetch.
- A reopened console is a **new** session id, never a reattach: the original console's
  session may still be open (or long closed) — the point is a second console resuming
  the same conversation. Reopened ids are minted `<projectId>-<issue>-resume-<short>`
  (or `-main-<short>` when the original was a main-checkout console) so they show up in
  the issue's existing console list and survive reloads as ordinary reattaches.
- The resume command runs in the original console's working directory: Claude stores
  conversations per directory, so resuming from anywhere else would not find them. The
  directory is the original session's recorded one when that record still exists,
  otherwise re-derived from the id's shape (project root for `-main-` consoles, the
  issue's worktree path — recreated if needed — for worktree consoles).
- The resume command itself is composed server-side from `cmd` + a new `resume` query
  parameter on the terminal WebSocket (`claude --resume <id>` / `codex resume <id>`),
  never sent as a free-form command string.
- Visibility of listed sessions follows the #48 ownership rule already used for
  consoles: a row is visible when its original console has no recorded owner (including
  after the console was closed, which deletes the ownership record) or is owned by the
  caller.

## Deviations / notes
- none
