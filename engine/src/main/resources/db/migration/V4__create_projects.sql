-- A user-added project (#42): the engine checks out its default branch into its own
-- workarea directory, asynchronously. default_branch is unknown until the clone
-- completes (it is discovered, not requested), so it starts NULL; status moves
-- CLONING -> READY (with default_branch filled in) or CLONING -> FAILED.
CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    git_url TEXT NOT NULL,
    workarea_path TEXT NOT NULL,
    default_branch TEXT,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL
);
