-- Backup codes (#93): a one-time-use way back into an account with 2FA on when the
-- authenticator device is lost. A set is generated when 2FA is confirmed on
-- (AccountTwoFactorController) and can be replaced from the settings dialog.
-- code_hash is a BCrypt hash of the plaintext code shown to the user exactly once,
-- the same pattern used for the account password -- the plaintext is never stored.
-- used_at is NULL until a login consumes the code, so each one works exactly once.
CREATE TABLE IF NOT EXISTS backup_codes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    code_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    used_at TEXT
);
