package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consumer outside this package (Web Push, #860) hears every session's attention
 * transitions through {@link SessionRegistry#addAttentionListener}, the same funnel
 * shape as {@link SessionRegistry#addCloseListener} -- including for a session
 * created before the listener was registered, and with a failing listener never
 * costing the others or the session.
 */
class SessionRegistryAttentionListenerTest {

    private record Heard(String sessionId, PtySession.AttentionState state, PtySession.WaitingReason reason) {
    }

    @Test
    void aListenerHearsEverySessionsTransitionsWithTheSessionId(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        PtySession earlier = registry.attach("42-7-before-listener", workDir);
        List<Heard> heard = new CopyOnWriteArrayList<>();
        registry.addAttentionListener((id, state, reason) -> heard.add(new Heard(id, state, reason)));
        PtySession later = registry.attach("42-8-after-listener", workDir);

        long wellPastTheThreshold = System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000;
        earlier.checkQuiescence(wellPastTheThreshold);
        later.checkQuiescence(wellPastTheThreshold);
        later.markFocused();

        assertThat(heard).containsExactly(
                new Heard("42-7-before-listener", PtySession.AttentionState.WAITING, PtySession.WaitingReason.QUIET),
                new Heard("42-8-after-listener", PtySession.AttentionState.WAITING, PtySession.WaitingReason.QUIET),
                new Heard("42-8-after-listener", PtySession.AttentionState.ACTIVE, null));
        registry.close("42-7-before-listener");
        registry.close("42-8-after-listener");
    }

    @Test
    void aFailingListenerIsContainedAndTheRestStillHear(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        registry.addAttentionListener((id, state, reason) -> {
            throw new IllegalStateException("boom");
        });
        List<Heard> heard = new CopyOnWriteArrayList<>();
        registry.addAttentionListener((id, state, reason) -> heard.add(new Heard(id, state, reason)));
        PtySession session = registry.attach("42-7-slug", workDir);

        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);

        assertThat(heard).containsExactly(new Heard("42-7-slug", PtySession.AttentionState.WAITING, PtySession.WaitingReason.QUIET));
        assertThat(registry.waitingSessions()).hasSize(1);
        registry.close("42-7-slug");
    }
}
