package dev.locklane.engine.pty;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.uploads.SessionUploadStorage;
import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ending a session for good (#436) removes the files uploaded onto its terminal —
 * every closer funnels through {@link SessionRegistry#close}, so the hook lives
 * there rather than being each caller's job to remember.
 */
class SessionRegistryUploadCleanupTest {

    @Test
    void closingASessionDeletesItsUploads(@TempDir Path dbDir, @TempDir Path uploadsDir) throws Exception {
        SessionUploadStorage storage = new SessionUploadStorage(uploadsDir.toString(), 1024);
        storage.store("42-7-slug", "shot.png", new ByteArrayInputStream(new byte[] {1}));
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir), null,
                new EventBroadcaster(new ObjectMapper()), storage);

        registry.close("42-7-slug");

        assertThat(uploadsDir.resolve("42-7-slug")).doesNotExist();
    }

    @Test
    void closingASessionWithNoUploadsStillSucceeds(@TempDir Path dbDir, @TempDir Path uploadsDir) {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir), null,
                new EventBroadcaster(new ObjectMapper()), new SessionUploadStorage(uploadsDir.toString(), 1024));

        registry.close("42-7-never-uploaded-to");

        assertThat(uploadsDir).isEmptyDirectory();
    }
}
