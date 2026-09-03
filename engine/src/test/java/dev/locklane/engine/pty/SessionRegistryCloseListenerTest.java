package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session-scoped resource outside this package (code-server's process, #628) hooks
 * its own cleanup onto {@link SessionRegistry#close} via {@link
 * SessionRegistry#addCloseListener}, the same funnel {@code uploadStorage} already uses
 * internally.
 */
class SessionRegistryCloseListenerTest {

    @Test
    void closingASessionNotifiesEveryRegisteredListener(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        SessionRegistry registry = new SessionRegistry(repository);
        List<String> notified = new ArrayList<>();
        registry.addCloseListener(notified::add);

        registry.close("1-174-rename-toggle");

        assertThat(notified).containsExactly("1-174-rename-toggle");
    }

    @Test
    void closingAnUnknownSessionStillNotifiesListeners(@TempDir Path dbDir) {
        // Mirrors uploadStorage's own unconditional cleanup: a listener may have state
        // to release for this id even when SessionRegistry itself never had one open.
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        SessionRegistry registry = new SessionRegistry(repository);
        List<String> notified = new ArrayList<>();
        registry.addCloseListener(notified::add);

        registry.close("never-opened");

        assertThat(notified).containsExactly("never-opened");
    }
}
