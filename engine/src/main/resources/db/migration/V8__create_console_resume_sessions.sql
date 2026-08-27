-- Resume ids captured from console output (#102): when a Claude/Codex session's
-- output reveals the id that `claude --resume <id>` / `codex resume <id>` accepts,
-- it is stored here, keyed by the console (worktree_sessions.worktree_id) it was
-- seen in. Deliberately never deleted when a console session is closed -- keeping
-- the conversation reachable after the process is gone is the point (#101).
-- captured_at is refreshed when the same id is seen again.
CREATE TABLE IF NOT EXISTS console_resume_sessions (
    worktree_id TEXT NOT NULL,
    tool TEXT NOT NULL,
    resume_id TEXT NOT NULL,
    captured_at TEXT NOT NULL,
    PRIMARY KEY (worktree_id, resume_id)
);
