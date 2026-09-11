package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #790's registry half: {@link SessionRegistry#waitingSessions()} names exactly
 * the live sessions currently in {@link PtySession.AttentionState#WAITING}, each with
 * its current {@link PtySession.WaitingReason} (#854) — what the events channel sends
 * a newly connected client as its catch-up snapshot — and nothing for a session that
 * is active, or that has a persisted record but no live process. Attention is driven
 * through the deterministic {@code checkQuiescence(nowMs)} overload, the same way
 * {@link PtySessionAttentionTest} does, so no test here sleeps.
 */
class SessionRegistryWaitingSessionsTest {

    @Test
    void noSessionIsWaitingWhenNothingIsLive(@TempDir Path dbDir) {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));

        assertThat(registry.waitingSessions()).isEmpty();
    }

    @Test
    void onlyTheSessionsCurrentlyWaitingAreListedWithTheirReason(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        PtySession waiting = registry.attach("42-7-waiting", workDir);
        PtySession alsoWaiting = registry.attach("42-8-also-waiting", workDir);
        registry.attach("42-9-active", workDir);

        long wellPastTheThreshold = System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000;
        waiting.checkQuiescence(wellPastTheThreshold);
        alsoWaiting.checkQuiescence(wellPastTheThreshold);

        assertThat(registry.waitingSessions()).containsExactlyInAnyOrder(
                new SessionRegistry.WaitingSession("42-7-waiting", PtySession.WaitingReason.QUIET),
                new SessionRegistry.WaitingSession("42-8-also-waiting", PtySession.WaitingReason.QUIET));
    }

    @Test
    void aSessionLeavesTheListOnceItIsActiveAgain(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        PtySession session = registry.attach("42-7-slug", workDir);
        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);
        assertThat(registry.waitingSessions())
                .containsExactly(new SessionRegistry.WaitingSession("42-7-slug", PtySession.WaitingReason.QUIET));

        session.markFocused();

        assertThat(registry.waitingSessions()).isEmpty();
    }

    @Test
    void aClosedSessionIsNoLongerListedEvenIfItWasWaiting(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        PtySession session = registry.attach("42-7-slug", workDir);
        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);

        registry.close("42-7-slug");

        assertThat(registry.waitingSessions()).isEmpty();
    }
}
