package dev.locklane.engine.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #5's done-when: a new attach reaches the same already-running session,
 * with output produced while no client was attached still present.
 */
class SessionRegistryReattachTest {

    @Test
    void startsOneSessionPerWorktreeAndKeepsItRunning(@TempDir Path workDir) {
        SessionRegistry registry = new SessionRegistry();
        PtySession session = registry.attach("worktree-a", workDir);

        assertThat(session.worktreeId()).isEqualTo("worktree-a");
        assertThat(session.isAlive()).isTrue();
    }

    @Test
    void reattachingReturnsTheSameRunningSessionWithItsBufferedOutput(@TempDir Path workDir) {
        SessionRegistry registry = new SessionRegistry();
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
        SessionRegistry registry = new SessionRegistry();
        PtySession a = registry.attach("worktree-c", workDir);
        PtySession b = registry.attach("worktree-d", workDir);

        assertThat(a).isNotSameAs(b);
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
