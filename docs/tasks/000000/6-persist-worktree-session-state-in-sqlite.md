# 6 — Persist worktree/session state in SQLite
Issue: #6 · Part of: #1

## Asked
Add a SQLite-backed persistence layer to the `engine/` module so worktree/session
state survives a server restart. Reference the old locklane repo
(`/Users/haninaguib/Projects/haninaguib/locklane/engine`) for its SQLite schema as a
pattern only — do not copy its files; write this repo's own implementation.

## Done when
- A SQLite database persists worktree/session state.
- After a server restart, a reconnecting client sees state consistent with what was
  persisted before the restart, not just what was in memory.

## Explicitly not
- PTY session process management — delivered by #5.
- The network/WebSocket transport for streaming terminal I/O — split to #7.

## Decisions made along the way
- Interpreted "worktree/session state" as the durable metadata (worktree id, working
  directory, created/last-attached timestamps) — not the live terminal's
  scrollback/output buffer, which is tied to the OS process and cannot outlive a JVM
  restart regardless of storage. Added `SessionRegistry.lastKnownWorkingDirectory()`
  so a caller can see this metadata for a worktree even with no live session in the
  current process — the concrete, testable form of "a reconnecting client sees state
  consistent with what was persisted" (haninaguib, 2026-08-25).
- The old repo's `engine/` has no SQLite persistence code to reference (checked: no
  SQLite dependency in its `pom.xml`, no schema/repository files under its
  `src/main`) — this task's persistence design has no prior pattern to draw from and
  is original to this repo (haninaguib, 2026-08-25).
- Chose `JdbcTemplate` + `org.xerial:sqlite-jdbc` + Boot's `schema.sql`
  auto-initialization over an ORM (JPA/Hibernate) — one table does not need a
  dialect-mapping layer, and this keeps `WorktreeSessionRepository` constructible
  directly in tests without a Spring context, matching #5's testing style
  (haninaguib, 2026-08-25).
- `SessionRegistry` now requires a `WorktreeSessionRepository` in its constructor
  (previously no-arg). Updated `SessionRegistryReattachTest` (from #5) accordingly and
  added a test proving state is visible across two independent `SessionRegistry`
  instances sharing only the persisted database — the in-process stand-in for a
  restart (haninaguib, 2026-08-25).
- Data directory defaults to `${user.home}/.locklane` (`locklane.data-dir`), echoing
  the directory convention the old repo uses for its own per-tool state
  (`${user.home}/.locklane/codex-home`) — reused as a convention, not code. Overridden
  to a temp directory in `engine/src/test/resources/application.yml` so tests never
  touch the real home directory (haninaguib, 2026-08-25).

## Deviations / notes
- none
