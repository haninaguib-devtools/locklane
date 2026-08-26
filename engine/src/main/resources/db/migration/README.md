Flyway migration history for the engine's SQLite schema (#107).

Each file here runs exactly once, in order, against whatever state a database is
already in — production's real `~/.locklane/locklane.db`, a test database, or a
brand-new one. **Never edit an existing migration.** Adding a column, table, or index
is always a new `V<next>__description.sql` (or a Java migration under
`dev.locklane.engine.persistence.migration` for anything SQLite's `ALTER TABLE` can't
express safely, such as a conditional `ADD COLUMN`) — editing a file that has already
run means different databases disagree about what ran, which is exactly what Flyway
exists to prevent.

Every step here is idempotent by construction: table-creation scripts use
`CREATE TABLE IF NOT EXISTS`, and column-adding steps are Java migrations that check
`PRAGMA table_info` before altering. That is what lets one linear history apply
correctly whether the target is empty, already fully caught up, or a legacy database
frozen at some older shape — no baseline step or version-guessing is needed.
