package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers #884's engine half: closing a session (#75) destroys its process without
 * the process itself ever emitting an `active` transition, so a session closed
 * while {@link PtySession.AttentionState#WAITING} would otherwise stay marked
 * waiting for every connected client forever, with the deterministic-id reuse case
 * ({@code WorktreeCreationService#startSession}) then showing a brand-new session as
 * waiting the moment it starts. {@link SessionRegistry#close} broadcasts a final
 * {@code consoleAttention} `active` for exactly that case, in the same shape the
 * live broadcast already uses.
 */
class SessionRegistryCloseAttentionTest {

    @Test
    void closingASessionThatWasWaitingBroadcastsAFinalActiveTransition(@TempDir Path dbDir, @TempDir Path workDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);
        PtySession session = registry.attach("42-7-slug", workDir);
        session.checkQuiescence(System.currentTimeMillis() + PtySession.QUIESCENCE_THRESHOLD_MS + 10_000);

        registry.close("42-7-slug");

        verify(broadcaster).broadcast("consoleAttention", Map.of("sessionId", "42-7-slug", "state", "active"));
    }

    @Test
    void closingASessionThatWasAlreadyActiveBroadcastsNoAttentionTransition(@TempDir Path dbDir, @TempDir Path workDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);
        registry.attach("42-7-slug", workDir);

        registry.close("42-7-slug");

        verify(broadcaster, never()).broadcast(eq("consoleAttention"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closingAnUnknownSessionBroadcastsNoAttentionTransition(@TempDir Path dbDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);

        registry.close("never-attached");

        verify(broadcaster, never()).broadcast(eq("consoleAttention"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void closingASessionWithNoLiveProcessBroadcastsNoAttentionTransition(@TempDir Path dbDir, @TempDir Path workDir) {
        var sharedRepository = TestSqliteDatabases.newRepository(dbDir);
        new SessionRegistry(sharedRepository).attach("42-7-slug", workDir);

        // A fresh registry instance sharing only the persisted state -- standing in
        // for this process having restarted, same as SessionRegistryAgentSessionsChangedTest's
        // own restart case: nothing live here for close() to read attention off of.
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry afterRestart = new SessionRegistry(sharedRepository, null, broadcaster);

        afterRestart.close("42-7-slug");

        verify(broadcaster, never()).broadcast(eq("consoleAttention"), org.mockito.ArgumentMatchers.any());
    }

    private static SessionRegistry newRegistry(Path dbDir, EventBroadcaster broadcaster) {
        return new SessionRegistry(TestSqliteDatabases.newRepository(dbDir), null, broadcaster);
    }
}
