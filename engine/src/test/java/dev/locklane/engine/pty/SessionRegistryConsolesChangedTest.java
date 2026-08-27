package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Covers #195's done-when as it applies to the engine: a genuinely new console
 * opening or an open console closing broadcasts {@code consolesChanged} with the
 * project id parsed out of the session id, while a reattach that changes nothing
 * a client-visible listing would show broadcasts nothing.
 */
class SessionRegistryConsolesChangedTest {

    @Test
    void attachingABrandNewConsoleBroadcastsConsolesChangedWithItsProjectId(@TempDir Path dbDir,
            @TempDir Path workDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);

        registry.attach("42-7-slug", workDir);

        verify(broadcaster).broadcast("consolesChanged", Map.of("projectId", 42L));
    }

    @Test
    void reattachingAnAlreadyLiveConsoleBroadcastsNothingFurther(@TempDir Path dbDir, @TempDir Path workDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);
        registry.attach("42-7-slug", workDir);

        registry.attach("42-7-slug", workDir);

        verify(broadcaster, times(1)).broadcast("consolesChanged", Map.of("projectId", 42L));
    }

    @Test
    void reattachingAfterARestartWithAPersistedRecordButNoLiveSessionBroadcastsNothing(@TempDir Path dbDir,
            @TempDir Path workDir) {
        WorktreeSessionRepository sharedRepository = TestSqliteDatabases.newRepository(dbDir);
        new SessionRegistry(sharedRepository).attach("42-7-slug", workDir);

        // A fresh registry instance, sharing only the persisted state -- standing in
        // for this process having restarted (SessionRegistryReattachTest does the
        // same for lastKnownWorkingDirectory).
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry afterRestart = new SessionRegistry(sharedRepository, null, broadcaster);

        afterRestart.attach("42-7-slug", workDir);

        verifyNoInteractions(broadcaster);
    }

    @Test
    void attachingWithNoParseableProjectIdBroadcastsWithNoProjectIdField(@TempDir Path dbDir, @TempDir Path workDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);

        registry.attach("no-numeric-prefix", workDir);

        verify(broadcaster).broadcast("consolesChanged");
    }

    @Test
    void closingAnOpenConsoleBroadcastsConsolesChanged(@TempDir Path dbDir, @TempDir Path workDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);
        registry.attach("42-7-slug", workDir);

        registry.close("42-7-slug");

        // Once for the open above, once more for this close.
        verify(broadcaster, times(2)).broadcast("consolesChanged", Map.of("projectId", 42L));
    }

    @Test
    void closingAnUnknownConsoleBroadcastsNothing(@TempDir Path dbDir) {
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        SessionRegistry registry = newRegistry(dbDir, broadcaster);

        registry.close("never-attached");

        verifyNoInteractions(broadcaster);
    }

    private static SessionRegistry newRegistry(Path dbDir, EventBroadcaster broadcaster) {
        return new SessionRegistry(TestSqliteDatabases.newRepository(dbDir), null, broadcaster);
    }
}
