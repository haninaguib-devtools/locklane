# 5 — Build PTY-per-worktree session core and wire up build/test checks
Issue: #5 · Part of: #1

## Asked
Build the persistent-PTY-per-worktree session core in a new Spring Boot module
(`engine/`): one long-lived PTY (pseudo-terminal) process per git worktree, independent
of any single client connection, kept alive when a client disconnects and reattachable
by a new connection (in-process reattachment — the network transport is a separate
task).

Because this is the first application code in the repo, it must also name the
project's build/test command in `AGENTS.md` under `## Checks` (currently "(none yet —
no stack exists.)") and add that command to `.github/workflows/ci.yml`, plus the
`.gitignore` entries a Maven build needs.

Reference the old locklane repo (`/Users/haninaguib/Projects/haninaguib/locklane/engine`)
for how it wired PTY sessions — do not copy its files; write this repo's own
implementation.

## Done when
- A Spring Boot application exists in `engine/` that can start a PTY process per
  worktree and keep it running independently of any single client connection.
- A new attempt to attach reaches the same already-running worktree's PTY session
  rather than starting a new one (in-process; a real network client is the WebSocket
  task's job).
- `AGENTS.md` `## Checks` names a real build/test command in place of "(none yet — no
  stack exists.)".
- `.github/workflows/ci.yml` runs that command on every PR.
- `.gitignore` excludes the new build output.

## Explicitly not
- SQLite persistence of worktree/session state — split to #6.
- A network endpoint (e.g. WebSocket) for streaming terminal I/O to a browser — split
  to #7.
- The Angular client — split to #3.

## Decisions made along the way
- Dependency and version choices mirror the old locklane repo's `engine/pom.xml`
  (Spring Boot 3.5.4, Java 21, `org.jetbrains.pty4j:pty4j:0.12.13` from its JetBrains
  vendor repository) — reused as factual technical parameters, not copied files, per
  the issue's reference-only constraint (haninaguib, 2026-08-25).
- Session core spawns the user's `$SHELL` (falling back to `/bin/bash`, then
  `/bin/sh`) in interactive mode as the PTY's command, rather than a fixed shell —
  keeps behavior close to a normal terminal (haninaguib, 2026-08-25).
- Output is drained continuously by a background thread into an in-memory buffer,
  regardless of whether a client is attached, so the process never blocks on a full
  pipe and a reattaching client sees everything produced while it was gone
  (haninaguib, 2026-08-25).

## Deviations / notes
- none
