-- Durable record of each worktree's session, independent of the in-memory PTY
-- process (dev.locklane.engine.pty). Survives a restart; the live process does not.
-- owner_username (#48) is NULL for a session created before per-user ownership
-- existed, or by an unauthenticated attach (no longer possible since #50 requires
-- auth on the WebSocket endpoint itself, but old rows can still carry it) — treated
-- as unclaimed, visible/attachable by any authenticated user, rather than orphaned.
CREATE TABLE IF NOT EXISTS worktree_sessions (
    worktree_id TEXT PRIMARY KEY,
    working_directory TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_attached_at TEXT NOT NULL,
    owner_username TEXT
);

-- Accounts (#47). password_hash is a BCrypt hash, never plaintext.
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
