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
 * overload directly so the threshold never needs a real sleep. Also covers #233: a
 * BEL that only terminates an OSC escape sequence is not a real attention signal.
 * Also covers #854: the reason (bell vs. quiet) carried alongside {@code WAITING},
 * including the quiet-to-bell upgrade and the bell-then-quiet non-downgrade. Also
 * covers #862: with the quiescence fallback flag off, {@code checkQuiescence} never
 * marks a session waiting, but a bell still does.
 */
class PtySessionAttentionTest {

    /** One {@link PtySession.AttentionState}/{@link PtySession.WaitingReason} pair, as delivered to a listener. */
    private record Attention(PtySession.AttentionState state, PtySession.WaitingReason reason, String message) {
    }

    @Test
    void bellMarksWaitingWithReasonBellAndSubsequentInputClearsIt(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-bell", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.write("printf '\\a'\n");
        waitUntil(() -> states.contains(new Attention(PtySession.AttentionState.WAITING, PtySession.WaitingReason.BELL, null)),
                Duration.ofSeconds(5));

        session.write("echo still-here\n");
        waitUntil(() -> !states.isEmpty()
                && states.get(states.size() - 1).equals(new Attention(PtySession.AttentionState.ACTIVE, null, null)),
                Duration.ofSeconds(5));
    }

    @Test
    void quiescenceMarksWaitingWithReasonQuietOnceOutputHasBeenSilentPastTheThreshold(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-quiescent", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        // Comfortably past the threshold, so a little startup output from the shell
        // itself (which nudges lastOutputAt forward on its own drain thread) can never
        // flip this into a false negative.
        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);

        assertThat(states).containsExactly(new Attention(PtySession.AttentionState.WAITING, PtySession.WaitingReason.QUIET, null));
    }

    @Test
    void quiescenceDoesNotFireBeforeTheThreshold(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-not-yet", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.checkQuiescence(System.currentTimeMillis());

        assertThat(states).isEmpty();
    }

    @Test
    void quietThenBellUpgradesTheReasonWithASecondEventStillWaiting(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-quiet-then-bell", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);
        assertThat(session.waitingReason()).isEqualTo(PtySession.WaitingReason.QUIET);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.write("printf '\\a'\n");

        waitUntil(() -> states.contains(new Attention(PtySession.AttentionState.WAITING, PtySession.WaitingReason.BELL, null)),
                Duration.ofSeconds(5));
        assertThat(session.attentionState()).isEqualTo(PtySession.AttentionState.WAITING);
        assertThat(session.waitingReason()).isEqualTo(PtySession.WaitingReason.BELL);
    }

    @Test
    void bellThenQuietKeepsTheBellReasonWithNoFurtherEvent(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-bell-then-quiet", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        session.write("printf '\\a'\n");
        waitUntil(() -> session.waitingReason() == PtySession.WaitingReason.BELL, Duration.ofSeconds(5));
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);

        assertThat(states).isEmpty();
        assertThat(session.waitingReason()).isEqualTo(PtySession.WaitingReason.BELL);
    }

    @Test
    void oscTitleSequenceBellDoesNotMarkWaiting(@TempDir Path workDir) {
        // #233: the OSC window-title convention (`ESC ] ... BEL`) Debian/Ubuntu's
        // default interactive bashrc emits on every prompt -- its terminating BEL is
        // punctuation for the sequence, not a real attention signal.
        PtySession session = new PtySession("attention-osc-title", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.write("printf '\\033]0;title\\a'\n");
        session.write("echo marker-after-osc-title\n");
        // The drain thread processes bytes in order, so once this later output has
        // been drained, the earlier OSC-title BEL has already been scanned too --
        // no sleep-and-hope needed to assert its absence.
        waitUntil(() -> session.bufferedOutput().contains("marker-after-osc-title"), Duration.ofSeconds(5));

        assertThat(states).isEmpty();
    }

    @Test
    void bareBellAfterAnOscTitleSequenceStillMarksWaiting(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-osc-then-bell", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.write("printf '\\033]0;title\\a'\n");
        session.write("printf '\\a'\n");
        waitUntil(() -> states.contains(new Attention(PtySession.AttentionState.WAITING, PtySession.WaitingReason.BELL, null)),
                Duration.ofSeconds(5));
    }

    @Test
    void attentionStateReadsTheCurrentStateWithoutASubscription(@TempDir Path workDir) {
        // #790: the accessor is what the events channel's connect-time snapshot reads,
        // since a subscription never replays the state at subscribe time.
        PtySession session = new PtySession("attention-accessor", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        assertThat(session.attentionState()).isEqualTo(PtySession.AttentionState.ACTIVE);
        assertThat(session.waitingReason()).isNull();

        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);
        assertThat(session.attentionState()).isEqualTo(PtySession.AttentionState.WAITING);
        assertThat(session.waitingReason()).isEqualTo(PtySession.WaitingReason.QUIET);

        session.markFocused();
        assertThat(session.attentionState()).isEqualTo(PtySession.AttentionState.ACTIVE);
        assertThat(session.waitingReason()).isNull();
    }

    @Test
    void focusClearsAttentionWithoutWritingToTheProcess(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-focus", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();

        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.markFocused();

        assertThat(states).containsExactly(new Attention(PtySession.AttentionState.ACTIVE, null, null));
    }

    @Test
    void withTheQuiescenceFallbackOffCheckQuiescenceNeverMarksWaitingButABellStillDoes(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-fallback-off", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24, false);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);
        assertThat(states).isEmpty();
        assertThat(session.attentionState()).isEqualTo(PtySession.AttentionState.ACTIVE);

        session.write("printf '\\a'\n");
        waitUntil(() -> states.contains(new Attention(PtySession.AttentionState.WAITING, PtySession.WaitingReason.BELL, null)),
                Duration.ofSeconds(5));
    }

    @Test
    void withTheQuiescenceFallbackOnBehaviourIsUnchanged(@TempDir Path workDir) {
        // The 6-arg constructor -- every existing call in this file -- keeps the
        // fallback on; this asserts the explicit `true` overload behaves identically.
        PtySession session = new PtySession("attention-fallback-on", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24, true);

        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);

        assertThat(session.attentionState()).isEqualTo(PtySession.AttentionState.WAITING);
        assertThat(session.waitingReason()).isEqualTo(PtySession.WaitingReason.QUIET);
    }

    @Test
    void oscNineNotificationMarksWaitingWithItsMessage(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-osc9", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.write("printf '\\033]9;Merge PR #851 into main?\\a'\n");
        waitUntil(() -> states.contains(
                new Attention(PtySession.AttentionState.WAITING, PtySession.WaitingReason.BELL, "Merge PR #851 into main?")),
                Duration.ofSeconds(5));
        assertThat(session.waitingMessage()).isEqualTo("Merge PR #851 into main?");
    }

    @Test
    void oscSevenSevenSevenNotifyPrefersItsBody(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-osc777", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.write("printf '\\033]777;notify;Title here;Body here\\a'\n");
        waitUntil(() -> states.contains(
                new Attention(PtySession.AttentionState.WAITING, PtySession.WaitingReason.BELL, "Body here")),
                Duration.ofSeconds(5));
    }

    @Test
    void oscNineProgressNeverMarksWaitingAndOscTitleBellStillDoesNot(@TempDir Path workDir) {
        PtySession session = new PtySession("attention-osc-progress", workDir,
                new String[] {"/bin/sh", "-i"}, Map.of(), 80, 24);
        List<Attention> states = new CopyOnWriteArrayList<>();
        session.subscribeAttention((state, reason, message) -> states.add(new Attention(state, reason, message)));

        session.write("printf '\\033]9;4;1;50\\a'\n");
        session.write("printf '\\033]0;title\\a'\n");
        session.write("echo marker-after-progress\n");
        waitUntil(() -> session.bufferedOutput().contains("marker-after-progress"), Duration.ofSeconds(5));

        assertThat(states).isEmpty();
        assertThat(session.waitingMessage()).isNull();
    }

    @Test
    void notificationMessageParsingCoversBothStandards() {
        assertThat(PtySession.notificationMessageFromOscPayload("9;Hello")).isEqualTo("Hello");
        assertThat(PtySession.notificationMessageFromOscPayload("9;4")).isNull();
        assertThat(PtySession.notificationMessageFromOscPayload("9;4;1;50")).isNull();
        assertThat(PtySession.notificationMessageFromOscPayload("0;title")).isNull();
        assertThat(PtySession.notificationMessageFromOscPayload("777;notify;Title;Body")).isEqualTo("Body");
        assertThat(PtySession.notificationMessageFromOscPayload("777;notify;Only title;")).isEqualTo("Only title");
        assertThat(PtySession.notificationMessageFromOscPayload("777;something-else;x")).isNull();
        assertThat(PtySession.sanitizeNotificationMessage("  a  b \n c ")).isEqualTo("a b c");
        assertThat(PtySession.sanitizeNotificationMessage("   ")).isNull();
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
