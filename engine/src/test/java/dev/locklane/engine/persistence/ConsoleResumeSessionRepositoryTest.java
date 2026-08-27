package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #102's persistence half: captured resume ids land in SQLite, the same id
 * seen again refreshes its timestamp instead of duplicating, and — the done-when
 * itself — a saved id is still there when the database is reopened the way a
 * restarted engine reopens it.
 */
class ConsoleResumeSessionRepositoryTest {

    @Test
    void recordsAndFindsResumeIdsPerConsole(@TempDir Path dbDir) {
        ConsoleResumeSessionRepository repository =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));

        repository.record("1-102-capture", "claude", "aaaa1111-e89b-42d3-a456-426614174000",
                Instant.parse("2026-08-27T10:00:00Z"));
        repository.record("1-102-capture", "codex", "bbbb2222-e89b-42d3-a456-426614174000",
                Instant.parse("2026-08-27T11:00:00Z"));
        repository.record("1-103-other", "claude", "cccc3333-e89b-42d3-a456-426614174000",
                Instant.parse("2026-08-27T12:00:00Z"));

        List<ConsoleResumeSessionRecord> found = repository.findByWorktree("1-102-capture");
        assertThat(found).containsExactly(
                new ConsoleResumeSessionRecord("1-102-capture", "claude",
                        "aaaa1111-e89b-42d3-a456-426614174000", Instant.parse("2026-08-27T10:00:00Z")),
                new ConsoleResumeSessionRecord("1-102-capture", "codex",
                        "bbbb2222-e89b-42d3-a456-426614174000", Instant.parse("2026-08-27T11:00:00Z")));
        assertThat(repository.findAll()).hasSize(3);
    }

    @Test
    void seeingTheSameIdAgainRefreshesTheTimestampWithoutDuplicating(@TempDir Path dbDir) {
        ConsoleResumeSessionRepository repository =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));

        repository.record("1-102-capture", "claude", "aaaa1111-e89b-42d3-a456-426614174000",
                Instant.parse("2026-08-27T10:00:00Z"));
        repository.record("1-102-capture", "claude", "aaaa1111-e89b-42d3-a456-426614174000",
                Instant.parse("2026-08-27T10:05:00Z"));

        assertThat(repository.findByWorktree("1-102-capture")).containsExactly(
                new ConsoleResumeSessionRecord("1-102-capture", "claude",
                        "aaaa1111-e89b-42d3-a456-426614174000", Instant.parse("2026-08-27T10:05:00Z")));
    }

    @Test
    void aSavedIdSurvivesReopeningTheDatabaseLikeARestartDoes(@TempDir Path dbDir) {
        DataSource beforeRestart = TestSqliteDatabases.newDataSource(dbDir);
        new ConsoleResumeSessionRepository(beforeRestart).record(
                "1-102-capture", "codex", "dddd4444-e89b-42d3-a456-426614174000",
                Instant.parse("2026-08-27T10:00:00Z"));

        // A second DataSource over the same on-disk file — sharing only the persisted
        // state, exactly what a restarted engine sees.
        ConsoleResumeSessionRepository afterRestart =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));

        assertThat(afterRestart.findByWorktree("1-102-capture")).containsExactly(
                new ConsoleResumeSessionRecord("1-102-capture", "codex",
                        "dddd4444-e89b-42d3-a456-426614174000", Instant.parse("2026-08-27T10:00:00Z")));
    }
}
