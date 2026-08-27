package dev.locklane.engine.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #130's done-when: a BEL byte in the output stream marks a session as
 * waiting, and subsequent input clears it back to active. Also covers the
 * quiescence fallback for an agent that never rings the bell — {@link
 * SessionRegistry#checkQuiescence()} is what calls {@link PtySession#checkQuiescence()}
 * on a schedule in production; these tests call the deterministic {@code (nowMs)}
 * overload directly so the threshold never needs a real sleep.
 */
class PtySessionAttentionTest {

    @Test
    void bellMarksWaitingAndSubsequentInputClearsIt(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-bell", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<PtySession.AttentionState> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention(states::add);

        session.write("printf '\\a'\n");
        waitUntil(() -> states.contains(PtySession.AttentionState.WAITING), Duration.ofSeconds(5));

        session.write("echo still-here\n");
        waitUntil(() -> !states.isEmpty() && states.get(states.size() - 1) == PtySession.AttentionState.ACTIVE,
                Duration.ofSeconds(5));
    }

    @Test
    void quiescenceMarksWaitingOnceOutputHasBeenSilentPastTheThreshold(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-quiescent", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<PtySession.AttentionState> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention(states::add);

        // Comfortably past the threshold, so a little startup output from the shell
        // itself (which nudges lastOutputAt forward on its own drain thread) can never
        // flip this into a false negative.
        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);

        assertThat(states).containsExactly(PtySession.AttentionState.WAITING);
    }

    @Test
    void quiescenceDoesNotFireBeforeTheThreshold(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-not-yet", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<PtySession.AttentionState> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention(states::add);

        session.checkQuiescence(System.currentTimeMillis());

        assertThat(states).isEmpty();
    }

    @Test
    void focusClearsAttentionWithoutWritingToTheProcess(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-focus", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<PtySession.AttentionState> states = new CopyOnWriteArrayList<>();

        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);
        session.subscribeAttention(states::add);

        session.markFocused();

        assertThat(states).containsExactly(PtySession.AttentionState.ACTIVE);
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
