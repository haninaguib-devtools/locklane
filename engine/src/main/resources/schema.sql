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
-- totp_secret (#88) is NULL until the user starts 2FA enrollment; when set, it is a
-- Base64 AES-GCM blob (TokenCipher) wrapping the Base32 TOTP secret, never plaintext.
-- totp_enabled distinguishes an enrollment that was started from one that was proved:
-- a secret with totp_enabled = 0 is pending (the user has scanned it but not yet
-- entered a matching code), and only totp_enabled = 1 means 2FA is actually on.
-- Disabling clears both back to NULL / 0.
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    totp_secret TEXT,
    totp_enabled INTEGER NOT NULL DEFAULT 0
);

-- A user-added project (#42): the engine checks out its default branch into its own
-- workarea directory, asynchronously. default_branch is unknown until the clone
-- completes (it is discovered, not requested), so it starts NULL; status moves
-- CLONING -> READY (with default_branch filled in) or CLONING -> FAILED.
-- github_token (#81) is NULL until the user stores one; when set, it is a Base64
-- AES-GCM blob (TokenCipher), never plaintext. NULL means issue/PR fetches for this
-- project fall back to whatever `gh` identity is already authenticated for its own
-- checkout directory, same as before this column existed.
CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    git_url TEXT NOT NULL,
    workarea_path TEXT NOT NULL,
    default_branch TEXT,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    github_token TEXT
);
