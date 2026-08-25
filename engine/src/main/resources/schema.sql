-- Durable record of each worktree's session, independent of the in-memory PTY
-- process (dev.locklane.engine.pty). Survives a restart; the live process does not.
CREATE TABLE IF NOT EXISTS worktree_sessions (
    worktree_id TEXT PRIMARY KEY,
    working_directory TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_attached_at TEXT NOT NULL
);
