-- Web Push subscriptions (#860, ADR-114): one row per browser that opted in to
-- "Notify me when an agent is waiting" while a service worker was active. Owned by
-- an account (owner_user_id, ADR-105) so an agent's bell reaches only the browsers
-- of the person who owns its project. endpoint is the push service's URL for that
-- browser and is unique by construction; p256dh is the browser's public key
-- (plaintext -- it is public), auth is the browser's 16-byte authentication secret,
-- encrypted by TokenCipher exactly like a GitHub token. A row goes when the user
-- turns the toggle off, when the push service answers 404/410 for it, or when the
-- owning account is deleted (UserCascadeDeleteService).
CREATE TABLE IF NOT EXISTS push_subscriptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL REFERENCES users(id),
    endpoint TEXT NOT NULL UNIQUE,
    p256dh TEXT NOT NULL,
    auth TEXT NOT NULL,
    created_at TEXT NOT NULL
);
