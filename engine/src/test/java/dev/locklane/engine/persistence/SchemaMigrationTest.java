package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
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
    void anExistingWorktreeSessionsTableGainsDisplayNameWithoutLosingRows(@TempDir Path dbDir) {
        // "Before the upgrade": a database migrated only as far as V10 -- the shape
        // worktree_sessions had before V11 added display_name (#393).
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "10");
        new JdbcTemplate(oldShape).update("""
                INSERT INTO worktree_sessions (worktree_id, working_directory, created_at, last_attached_at)
                VALUES (?, ?, ?, ?)
                """,
                "7-console-aaaaaaaa", "/work/console", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);
        WorktreeSessionRepository repository = new WorktreeSessionRepository(oldShape);

        // A session that predates naming keeps its row and simply carries no name,
        // which is what makes the client fall back to its auto-generated label.
        assertThat(repository.find("7-console-aaaaaaaa")).isPresent().get()
                .extracting(WorktreeSessionRecord::displayName).isNull();

        repository.setDisplayName("7-console-aaaaaaaa", "release notes");
        assertThat(repository.find("7-console-aaaaaaaa")).isPresent().get()
                .extracting(WorktreeSessionRecord::displayName).isEqualTo("release notes");

        // And clearing it puts the row back to carrying no name at all.
        repository.setDisplayName("7-console-aaaaaaaa", null);
        assertThat(repository.find("7-console-aaaaaaaa")).isPresent().get()
                .extracting(WorktreeSessionRecord::displayName).isNull();
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
    void anExistingProjectGetsBackfilledToTheAdminOwnerAndItsWorkareaIsRelocated(@TempDir Path dbDir) throws Exception {
        // V9 added role/must_change_password to users but projects still has no
        // owner_user_id; this is the shape an already-running single-user install
        // would be in right before this upgrade.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "9");
        JdbcTemplate jdbc = new JdbcTemplate(oldShape);
        jdbc.update("INSERT INTO users (username, password_hash, created_at, role) VALUES (?, ?, ?, ?)",
                "root", "bcrypt-hash", "2026-01-01T00:00:00Z", "ADMIN");
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, "root");

        Path oldWorkarea = dbDir.resolve("workareas").resolve("locklane");
        Files.createDirectories(oldWorkarea);
        Files.writeString(oldWorkarea.resolve("marker.txt"), "checked out before the upgrade");
        jdbc.update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                "locklane", "git@example.com:x/locklane.git", oldWorkarea.toString(), "main", "READY",
                "2026-01-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);
        ProjectRepository repository = new ProjectRepository(oldShape);

        Path expectedNewWorkarea = dbDir.resolve("workareas").resolve(String.valueOf(adminId)).resolve("locklane");
        ProjectRecord found = repository.findAll().stream().findFirst().orElseThrow();
        assertThat(found.ownerUserId()).isEqualTo(adminId);
        assertThat(found.workareaPath()).isEqualTo(expectedNewWorkarea);
        assertThat(repository.findByWorkareaPath(expectedNewWorkarea)).isPresent();

        // The actual directory moved, contents intact, and the old one is gone.
        assertThat(oldWorkarea).doesNotExist();
        assertThat(expectedNewWorkarea).isDirectory();
        assertThat(Files.readString(expectedNewWorkarea.resolve("marker.txt")))
                .isEqualTo("checked out before the upgrade");
    }

    @Test
    void aProjectWithNoOnDiskCheckoutYetIsBackfilledWithoutErroring(@TempDir Path dbDir) {
        // A project still CLONING (or FAILED with its directory already cleaned up)
        // has no directory to move — the migration must not fail just because the
        // path it was told about doesn't exist on disk.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "9");
        JdbcTemplate jdbc = new JdbcTemplate(oldShape);
        jdbc.update("INSERT INTO users (username, password_hash, created_at, role) VALUES (?, ?, ?, ?)",
                "root", "bcrypt-hash", "2026-01-01T00:00:00Z", "ADMIN");
        long adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, "root");
        Path neverCheckedOut = dbDir.resolve("workareas").resolve("still-cloning");
        jdbc.update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at)
                VALUES (?, ?, ?, NULL, ?, ?)
                """,
                "still-cloning", "url", neverCheckedOut.toString(), "CLONING", "2026-01-01T00:00:00Z");

        TestSqliteDatabases.migrateToLatest(oldShape);
        ProjectRecord found = new ProjectRepository(oldShape).findAll().stream().findFirst().orElseThrow();

        assertThat(found.ownerUserId()).isEqualTo(adminId);
        assertThat(found.workareaPath())
                .isEqualTo(dbDir.resolve("workareas").resolve(String.valueOf(adminId)).resolve("still-cloning"));
        assertThat(found.workareaPath()).doesNotExist();
    }

    @Test
    void anExistingProjectsTableGainsTemplateSeededAtWithoutLosingRows(@TempDir Path dbDir) {
        // V13 predates template_seeded_at (#537); V14 adds it.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "13");
        new JdbcTemplate(oldShape).update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at, owner_user_id,
                                      template)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "locklane", "git@example.com:x/locklane.git", "/work/locklane", "main", "READY",
                "2026-01-01T00:00:00Z", 1L, "springboot-angular");

        TestSqliteDatabases.migrateToLatest(oldShape);
        ProjectRepository repository = new ProjectRepository(oldShape);

        ProjectRecord found = repository.findByWorkareaPath(Path.of("/work/locklane")).orElseThrow();
        assertThat(found.template()).isEqualTo("springboot-angular");
        assertThat(found.templateSeededAt()).isNull();

        repository.markTemplateSeeded(found.id(), Instant.parse("2026-09-01T12:00:00Z"));
        assertThat(repository.findById(found.id())).isPresent().get()
                .extracting(ProjectRecord::templateSeededAt).isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
    }

    @Test
    void anExistingProjectsTableGainsTemplateWithoutLosingRows(@TempDir Path dbDir) {
        // V12 predates template (#536); V13 adds it.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "12");
        new JdbcTemplate(oldShape).update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at, owner_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                "locklane", "git@example.com:x/locklane.git", "/work/locklane", "main", "READY",
                "2026-01-01T00:00:00Z", 1L);

        TestSqliteDatabases.migrateToLatest(oldShape);
        ProjectRepository repository = new ProjectRepository(oldShape);

        ProjectRecord found = repository.findByWorkareaPath(Path.of("/work/locklane")).orElseThrow();
        assertThat(found.template()).isNull();

        ProjectRecord created = repository.create("templated", "url", Path.of("/work/templated"), 1L, Instant.now(),
                "springboot-angular");
        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::template).isEqualTo("springboot-angular");
    }

    @Test
    void anExistingProjectsTableGainsAccentColorWithoutLosingRows(@TempDir Path dbDir) {
        // V11 predates accent_color (#427); V12 adds it.
        DataSource oldShape = TestSqliteDatabases.newDataSourceAtVersion(dbDir, "11");
        new JdbcTemplate(oldShape).update("""
                INSERT INTO projects (name, git_url, workarea_path, default_branch, status, created_at, owner_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                "locklane", "git@example.com:x/locklane.git", "/work/locklane", "main", "READY",
                "2026-01-01T00:00:00Z", 1L);

        TestSqliteDatabases.migrateToLatest(oldShape);
        ProjectRepository repository = new ProjectRepository(oldShape);

        ProjectRecord found = repository.findByWorkareaPath(Path.of("/work/locklane")).orElseThrow();
        assertThat(found.accentColor()).isNull();

        repository.setAccentColor(found.id(), "#c15f3c");
        assertThat(repository.findById(found.id())).isPresent().get()
                .extracting(ProjectRecord::accentColor).isEqualTo("#c15f3c");
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
