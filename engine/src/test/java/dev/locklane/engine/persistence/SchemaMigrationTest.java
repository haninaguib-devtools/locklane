package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #107's done-when directly: a database left in an older shape by an earlier
 * version of the schema really gains the columns a later migration adds, and does
 * not lose the rows written before that migration ran — not just get stamped as
 * up to date.
 */
class SchemaMigrationTest {

    @Test
    void anExistingWorktreeSessionsTableGainsOwnerUsernameWithoutLosingRows(@TempDir Path dbDir) {
        // "Before the restart": a database migrated only as far as V2 — the shape
        // worktree_sessions had before V3 added owner_username.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "2");
        new JdbcTemplate(oldShape).update("""
                INSERT INTO worktree_sessions (worktree_id, working_directory, created_at, last_attached_at)
                VALUES (?, ?, ?, ?)
                """,
                "worktree-old", "/work/old", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

        // "After the restart": the same on-disk file, brought current the same way
        // production does on every startup.
        TestSqliteDatabases.migrateToLatest(oldShape);
        WorktreeSessionRepository repository = new WorktreeSessionRepository(oldShape);

        Optional<WorktreeSessionRecord> found = repository.find("worktree-old");
        assertThat(found).isPresent();
        assertThat(found.get().workingDirectory().toString()).isEqualTo("/work/old");
        assertThat(found.get().ownerUsername()).isNull();

        // A fresh attach after the upgrade proves the new column actually works, not
        // just that it exists.
        repository.recordAttach("worktree-new", dbDir.resolve("new"), Instant.parse("2026-01-02T00:00:00Z"), "alice");
        assertThat(repository.find("worktree-new")).isPresent().get()
                .extracting(WorktreeSessionRecord::ownerUsername).isEqualTo("alice");
    }

    @Test
    void anExistingProjectsTableGainsGithubTokenWithoutLosingRows(@TempDir Path dbDir) {
        // V4 created projects; V5 (the next one) adds github_token.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "4");
        new JdbcTemplate(oldShape).update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                "locklane", "git@example.com:x/locklane.git", "/work/locklane", "main", "READY", "2026-01-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);
        ProjectRepository repository = new ProjectRepository(oldShape);

        ProjectRecord found = repository.findByWorkareaPath(Path.of("/work/locklane")).orElseThrow();
        assertThat(found.name()).isEqualTo("locklane");
        assertThat(repository.findGithubToken(found.id())).isEmpty();

        repository.setGithubToken(found.id(), "encrypted-token");
        assertThat(repository.findGithubToken(found.id())).contains("encrypted-token");
    }

    @Test
    void anExistingUsersTableGainsTotpColumnsWithoutLosingRows(@TempDir Path dbDir) {
        // V2 created users without totp_secret/totp_enabled; V6 adds them.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "2");
        new JdbcTemplate(oldShape).update(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                "alice", "bcrypt-hash", "2026-01-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);
        UserRepository repository = new UserRepository(oldShape);

        UserRecord found = repository.findByUsername("alice").orElseThrow();
        assertThat(found.passwordHash()).isEqualTo("bcrypt-hash");
        assertThat(found.totpSecret()).isNull();
        assertThat(found.totpEnabled()).isFalse();

        repository.startTotpEnrollment("alice", "encrypted-secret");
        assertThat(repository.findByUsername("alice")).isPresent().get()
                .extracting(UserRecord::totpSecret).isEqualTo("encrypted-secret");
    }

    @Test
    void anExistingUsersTableBackfillsToAdminRoleWithoutLosingRows(@TempDir Path dbDir) {
        // V2 created users without role/must_change_password; V9 adds them, backfilling
        // any pre-existing row (an existing single-user install) to ADMIN so that
        // account keeps full access rather than being silently demoted.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "2");
        new JdbcTemplate(oldShape).update(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                "dana", "bcrypt-hash", "2026-01-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);
        UserRecord found = new UserRepository(oldShape).findByUsername("dana").orElseThrow();

        assertThat(found.role()).isEqualTo(UserRecord.Role.ADMIN);
        assertThat(found.mustChangePassword()).isFalse();
    }

    @Test
    void anExistingDatabaseGainsTheBackupCodesTableWithoutLosingUsers(@TempDir Path dbDir) {
        // V2 created users; the backup_codes table (#93) is V7, added long after.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "6");
        new JdbcTemplate(oldShape).update(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                "carol", "bcrypt-hash", "2026-01-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);
        UserRecord user = new UserRepository(oldShape).findByUsername("carol").orElseThrow();

        BackupCodeRepository repository = new BackupCodeRepository(oldShape);
        assertThat(repository.findUnused(user.id())).isEmpty();

        repository.replace(user.id(), List.of("hash-1", "hash-2"), Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(repository.findUnused(user.id())).hasSize(2);
    }

    @Test
    void anExistingDatabaseGainsTheConsoleResumeSessionsTableWithoutLosingWorktreeSessions(@TempDir Path dbDir) {
        // V1 created worktree_sessions; the console_resume_sessions table (#102) is
        // V8, added long after.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "7");
        new JdbcTemplate(oldShape).update("""
                INSERT INTO worktree_sessions (worktree_id, working_directory, created_at, last_attached_at)
                VALUES (?, ?, ?, ?)
                """,
                "1-102-console", "/work/102", "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);

        assertThat(new WorktreeSessionRepository(oldShape).find("1-102-console")).isPresent();
        ConsoleResumeSessionRepository repository = new ConsoleResumeSessionRepository(oldShape);
        assertThat(repository.findByWorktree("1-102-console")).isEmpty();

        repository.record("1-102-console", "claude", "123e4567-e89b-42d3-a456-426614174000",
                Instant.parse("2026-08-27T10:00:00Z"));
        assertThat(repository.findByWorktree("1-102-console")).hasSize(1);
    }

    @Test
    void aDatabaseLeftAtAnOlderVersionByAPreviousTestRunMigratesCleanlyOnTheNextOne(@TempDir Path dbDir) {
        // Stands in for a leftover locklane-engine-test directory from a run made
        // before a migration existed: the directory is there, but the schema in it
        // predates the latest migration. Reopening it the normal way — the same
        // TestSqliteDatabases.newDataSource() every other test calls — must pick up
        // the rest of the history, not fail with "no such column" or require the
        // directory to be deleted by hand first.
        DataSource leftover = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "4");
        new JdbcTemplate(leftover).update(
                "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
                "bob", "bcrypt-hash", "2026-01-01T00:00:00Z");

        DataSource reopened = TestSqliteDatabases.newDataSource(dbDir);

        Optional<UserRecord> found = new UserRepository(reopened).findByUsername("bob");
        assertThat(found).isPresent();
        assertThat(found.get().totpEnabled()).isFalse();
    }
}
