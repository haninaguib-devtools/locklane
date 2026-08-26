# 107 — Manage the engine's SQLite schema with Flyway migrations

Issue: #107

## Asked

Today, when a task adds a column to the engine's database, an existing installation never
gets it. The whole schema lives in one file, `engine/src/main/resources/schema.sql`, built
from `CREATE TABLE IF NOT EXISTS` statements re-run on every startup — so once a table
exists the statement is skipped and the new column is silently absent. The only way to
pick up a schema change is to delete `~/.locklane/locklane.db` and lose everything in it.
The same defect hits the test suite, where `TestSqliteDatabases` hand-splits the same
`schema.sql` against a database file that survives between runs.

Replace the single re-run script with Flyway, so the schema is a sequence of versioned
migration files that each apply exactly once, in order, to whatever state a database is
already in.

## Done when

- Flyway runs against the engine's SQLite database on startup, and the schema is defined
  by versioned files under `engine/src/main/resources/db/migration/`.
- `engine/src/main/resources/schema.sql` no longer exists and `spring.sql.init.mode:
  always` is gone from both `application.yml` files — `grep -rn "schema.sql\|sql.init"
  engine/src` returns nothing.
- An existing database really gains the columns, not just a version stamp: a test creates
  a database in the pre-migration shape, runs the migration path over it, and asserts both
  that every current column is present and that rows written beforehand survive.
- Tests build their database through the same migration path as production;
  `TestSqliteDatabases` no longer reads or splits `/schema.sql`, and its hand-rolled
  statement splitter is gone.
- A leftover `${java.io.tmpdir}/locklane-engine-test` directory from a previous run no
  longer breaks the suite after a schema change.
- Adding a future column is one new file in `db/migration/`; no existing migration file is
  edited. Stated alongside the migrations so a cold session follows it.
- `./mvnw -B test` and `./scripts/consistency-check.sh` pass.

## Explicitly not

- No change to what the schema contains — the migrations end in exactly the tables and
  columns `schema.sql` produced when this task started, including #88's `totp_secret` and
  `totp_enabled`.
- No move off SQLite, and no support for a second database engine.
- No backfill or reshaping of existing rows; structure only.
- No rate-limiting, TOTP, or account work.

## Decisions made along the way

- **The migration set reproduces the schema's real history, not just its end state**
  (Claude, 2026-08-26). `V1`/`V2`/`V4` create `worktree_sessions`, `users`, and
  `projects` in the shape they had when each table was first added; `V3`, `V5`, `V6`
  add `owner_username`, `github_token`, and the two `totp_*` columns respectively, in
  the order the original commits added them. A single full-shape baseline would have
  satisfied a fresh install but would have left an existing database stamped-but-
  unchanged — the exact failure the issue forbids. Splitting the history is what lets
  an old database actually gain its missing columns.

- **Every migration is idempotent by construction, so no per-version probing step is
  needed** (Claude, 2026-08-26). SQLite has no `ADD COLUMN IF NOT EXISTS` (confirmed
  against the bundled sqlite3 CLI), so a plain `ALTER TABLE` would fail with
  "duplicate column name" against a database whose table already carries a later
  column — plausible here, since a table's shape is whatever the old startup script
  looked like at the moment it was first created, not a strict linear sequence.
  Table-creation steps stay plain `CREATE TABLE IF NOT EXISTS` SQL (already
  idempotent); the three column-adding steps are Java migrations
  (`dev.locklane.engine.persistence.migration`, kept inside the issue's scope rather
  than the default top-level `db.migration` package) that check `PRAGMA table_info`
  before altering. That means the *content* of V1 through V6 does the right thing
  against an empty database, a fully caught-up legacy one, or anything in between,
  with no per-table version-guessing logic to maintain.

- **Flyway still needs one blanket `baseline-on-migrate` setting, because its own
  safety check — not the migrations' content — is what a pre-Flyway database trips**
  (Claude, 2026-08-26; found by running the suite against a leftover
  `locklane-engine-test` directory built to the pre-Flyway shape). Flyway refuses to
  run at all against a schema that already has tables but no `flyway_schema_history`,
  regardless of whether the individual migrations could handle it — "Found non-empty
  schema(s) but no schema history table." `spring.flyway.baseline-on-migrate: true`
  with `baseline-version: 0` in both `application.yml` files (and the matching
  `Flyway.configure()` in `TestSqliteDatabases`) tells Flyway to trust the database
  and start counting from zero — below every real migration, so V1–V6 still run for
  real rather than being skipped. Verified directly: a database built to the exact
  shape the old `schema.sql` produced, with no history table, ended up with all six
  migrations recorded as genuinely run in `flyway_schema_history` and its
  pre-existing row intact.

- **Production and tests share one Flyway configuration, not a second description of
  the schema** (Claude, 2026-08-26). `spring.flyway.locations` in both
  `application.yml` files names the SQL and Java migration locations; Boot's own
  autoconfiguration applies them for the app and for `@SpringBootTest`s.
  `TestSqliteDatabases` (no Spring context available) builds the identical
  `Flyway.configure()...locations(...)` by hand for the handful of tests that
  construct a repository directly — `newDataSourceAtVersion` additionally exposes a
  `target` version, so `SchemaMigrationTest` can build a database at an old shape,
  write rows into it, then call `migrateToLatest` and assert both the new columns and
  the old rows are there — exercising the exact upgrade path production takes.

## Deviations / notes

- none
