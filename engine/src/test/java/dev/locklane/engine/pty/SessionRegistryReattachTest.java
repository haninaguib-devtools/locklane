package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #5's done-when: a new attach reaches the same already-running session,
 * with output produced while no client was attached still present. Also covers
 * #6's done-when as it applies to this class: a worktree's last-known state is
 * visible even when this process has no live session for it (SessionRegistryDatabase
 * dev.locklane.engine.persistence.WorktreeSessionRepositoryTest covers the
 * restart-survival itself, at the repository level).
 */
class SessionRegistryReattachTest {

    @Test
    void startsOneSessionPerWorktreeAndKeepsItRunning(@TempDir Path workDir) {
        SessionRegistry registry = newRegistry(workDir);
        PtySession session = registry.attach("worktree-a", workDir);

        assertThat(session.sessionId()).isEqualTo("worktree-a");
        assertThat(session.isAlive()).isTrue();
    }

    @Test
    void attachStartsTheGivenLaunchCommandInsteadOfTheDefaultShell(@TempDir Path workDir) {
        SessionRegistry registry = newRegistry(workDir);

        PtySession session = registry.attach("with-command", workDir, new String[] {"/bin/sh", "-c", "echo picked-command; exec /bin/sh"});

        waitUntil(() -> session.bufferedOutput().contains("picked-command"), Duration.ofSeconds(5));
    }

    @Test
    void twoSessionsCanShareTheSameWorkingDirectory(@TempDir Path workDir) {
        SessionRegistry registry = newRegistry(workDir);

        PtySession a = registry.attach("session-in-shared-dir-1", workDir);
        PtySession b = registry.attach("session-in-shared-dir-2", workDir);

        assertThat(a).isNotSameAs(b);
        assertThat(a.isAlive()).isTrue();
        assertThat(b.isAlive()).isTrue();
    }

    @Test
    void reattachingReturnsTheSameRunningSessionWithItsBufferedOutput(@TempDir Path workDir) {
        SessionRegistry registry = newRegistry(workDir);
        String worktreeId = "worktree-b";

        PtySession first = registry.attach(worktreeId, workDir);
        first.write("echo hello-from-locklane\n");
        waitUntil(() -> first.bufferedOutput().contains("hello-from-locklane"), Duration.ofSeconds(5));

        // Nothing is done to simulate "the client detaching" — the point is that the
        // session and its background drain thread keep running whether or not anyone
        // is currently reading from them.
        PtySession reattached = registry.attach(worktreeId, workDir);

        assertThat(reattached).isSameAs(first);
        assertThat(reattached.isAlive()).isTrue();
        assertThat(reattached.bufferedOutput()).contains("hello-from-locklane");

        reattached.write("echo still-alive\n");
        waitUntil(() -> reattached.bufferedOutput().contains("still-alive"), Duration.ofSeconds(5));
    }

    @Test
    void differentWorktreesGetDifferentSessions(@TempDir Path workDir) {
        SessionRegistry registry = newRegistry(workDir);
        PtySession a = registry.attach("worktree-c", workDir);
        PtySession b = registry.attach("worktree-d", workDir);

        assertThat(a).isNotSameAs(b);
    }

    @Test
    void lastKnownWorkingDirectoryIsVisibleWithNoLiveSessionInThisRegistry(@TempDir Path dbDir, @TempDir Path workDir) {
        WorktreeSessionRepository sharedRepository = TestSqliteDatabases.newRepository(dbDir);
        SessionRegistry firstRegistry = new SessionRegistry(sharedRepository);
        firstRegistry.attach("worktree-e", workDir);

        // A second, independent registry instance — standing in for a fresh process
        // after a restart — sharing only the persisted state, never the in-memory map.
        SessionRegistry secondRegistry = new SessionRegistry(sharedRepository);

        assertThat(secondRegistry.find("worktree-e")).isEmpty();
        assertThat(secondRegistry.lastKnownWorkingDirectory("worktree-e")).contains(workDir);
    }

    private static SessionRegistry newRegistry(Path dbDir) {
        return new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
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
