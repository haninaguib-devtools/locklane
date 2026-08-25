package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #6's done-when directly: state written before a restart is what a fresh
 * repository instance — standing in for the process restarting — reads back.
 */
class WorktreeSessionRepositoryTest {

    @Test
    void aRecordedAttachIsReadableFromTheSameRepository(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path workingDirectory = dbDir.resolve("worktree-a");
        Instant now = Instant.parse("2026-08-25T12:00:00Z");

        repository.recordAttach("worktree-a", workingDirectory, now);

        Optional<WorktreeSessionRecord> found = repository.find("worktree-a");
        assertThat(found).isPresent();
        assertThat(found.get().worktreeId()).isEqualTo("worktree-a");
        assertThat(found.get().workingDirectory()).isEqualTo(workingDirectory);
        assertThat(found.get().createdAt()).isEqualTo(now);
        assertThat(found.get().lastAttachedAt()).isEqualTo(now);
    }

    @Test
    void stateSurvivesASimulatedRestart(@TempDir Path dbDir) {
        Path workingDirectory = dbDir.resolve("worktree-b");
        Instant firstAttach = Instant.parse("2026-08-25T12:00:00Z");

        // "Before the restart": one repository instance, backed by the SQLite file.
        WorktreeSessionRepository beforeRestart = TestSqliteDatabases.newRepository(dbDir);
        beforeRestart.recordAttach("worktree-b", workingDirectory, firstAttach);

        // "After the restart": a brand new repository instance (nothing shared in
        // memory with the one above), pointed at the same on-disk database file —
        // exactly what happens when the JVM restarts and the file does not.
        WorktreeSessionRepository afterRestart = TestSqliteDatabases.newRepository(dbDir);

        Optional<WorktreeSessionRecord> found = afterRestart.find("worktree-b");
        assertThat(found).isPresent();
        assertThat(found.get().workingDirectory()).isEqualTo(workingDirectory);
        assertThat(found.get().createdAt()).isEqualTo(firstAttach);
    }

    @Test
    void reattachingUpdatesLastAttachedAtButKeepsCreatedAt(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path workingDirectory = dbDir.resolve("worktree-c");
        Instant created = Instant.parse("2026-08-25T12:00:00Z");
        Instant reattached = Instant.parse("2026-08-25T13:30:00Z");

        repository.recordAttach("worktree-c", workingDirectory, created);
        repository.recordAttach("worktree-c", workingDirectory, reattached);

        WorktreeSessionRecord record = repository.find("worktree-c").orElseThrow();
        assertThat(record.createdAt()).isEqualTo(created);
        assertThat(record.lastAttachedAt()).isEqualTo(reattached);
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void unknownWorktreeIsNotFound(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);

        assertThat(repository.find("never-seen")).isEmpty();
    }
}
