package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.ConsoleResumeSessionRecord;
import dev.locklane.engine.persistence.ConsoleResumeSessionRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #102 end to end at the registry level: a real PTY process whose output
 * contains a resume id gets that id persisted, and closing the console keeps the
 * captured id even though the console's own record is forgotten — reopening the
 * conversation later is the point (#101).
 */
class SessionRegistryResumeCaptureTest {

    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void aResumeIdPrintedByTheSessionIsPersisted(@TempDir Path dbDir, @TempDir Path workDir) {
        DataSource dataSource = TestSqliteDatabases.newDataSource(dbDir);
        ConsoleResumeSessionRepository resumeRepository = new ConsoleResumeSessionRepository(dataSource);
        SessionRegistry registry = new SessionRegistry(new WorktreeSessionRepository(dataSource), resumeRepository);

        registry.attach("1-102-resume", workDir, new String[] {
                "/bin/sh", "-c", "echo 'Resume with: claude --resume " + ID + "'; exec /bin/sh"});

        waitUntil(() -> !resumeRepository.findByWorktree("1-102-resume").isEmpty(), Duration.ofSeconds(5));
        List<ConsoleResumeSessionRecord> captured = resumeRepository.findByWorktree("1-102-resume");
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).tool()).isEqualTo("claude");
        assertThat(captured.get(0).resumeId()).isEqualTo(ID);
    }

    @Test
    void closingTheConsoleKeepsItsCapturedResumeId(@TempDir Path dbDir, @TempDir Path workDir) {
        DataSource dataSource = TestSqliteDatabases.newDataSource(dbDir);
        WorktreeSessionRepository sessionRepository = new WorktreeSessionRepository(dataSource);
        ConsoleResumeSessionRepository resumeRepository = new ConsoleResumeSessionRepository(dataSource);
        SessionRegistry registry = new SessionRegistry(sessionRepository, resumeRepository);
        registry.attach("1-102-close", workDir, new String[] {
                "/bin/sh", "-c", "echo 'run codex resume " + ID + "'; exec /bin/sh"});
        waitUntil(() -> !resumeRepository.findByWorktree("1-102-close").isEmpty(), Duration.ofSeconds(5));

        registry.close("1-102-close");

        // The console itself is forgotten (#75)…
        assertThat(sessionRepository.find("1-102-close")).isEmpty();
        // …but the conversation stays reachable.
        assertThat(resumeRepository.findByWorktree("1-102-close")).hasSize(1);
    }

    private static void waitUntil(Supplier<Boolean> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }
}
