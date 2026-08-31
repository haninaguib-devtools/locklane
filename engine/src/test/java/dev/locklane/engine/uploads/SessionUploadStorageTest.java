package dev.locklane.engine.uploads;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionUploadStorageTest {

    @Test
    void aTraversalShapedFilenameIsReducedToItsSafeBasename(@TempDir Path uploadsDir) throws IOException {
        SessionUploadStorage storage = new SessionUploadStorage(uploadsDir.toString(), 1024);

        Path stored = storage.store("1-174-s", "../../etc/evil name.png", new ByteArrayInputStream(new byte[] {1}));

        assertThat(stored.getParent()).isEqualTo(uploadsDir.resolve("1-174-s"));
        assertThat(stored.getFileName().toString()).isEqualTo("evil_name.png");
    }

    @Test
    void aFilenameThatSanitizesToNothingStillStores(@TempDir Path uploadsDir) throws IOException {
        SessionUploadStorage storage = new SessionUploadStorage(uploadsDir.toString(), 1024);

        Path stored = storage.store("1-174-s", "...", new ByteArrayInputStream(new byte[] {1}));

        assertThat(stored.getFileName().toString()).isEqualTo("upload");
    }

    @Test
    void aSessionIdThatIsNotAPlainPathSegmentIsRefused(@TempDir Path uploadsDir) {
        SessionUploadStorage storage = new SessionUploadStorage(uploadsDir.toString(), 1024);

        for (String bad : new String[] {"..", ".", "", "a/b", "a\\b"}) {
            assertThatThrownBy(() -> storage.store(bad, "shot.png", new ByteArrayInputStream(new byte[] {1})))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(uploadsDir).isEmptyDirectory();
    }

    @Test
    void deleteForRemovesTheSessionsFolderAndToleratesAMissingOne(@TempDir Path uploadsDir) throws IOException {
        SessionUploadStorage storage = new SessionUploadStorage(uploadsDir.toString(), 1024);
        storage.store("1-174-s", "a.png", new ByteArrayInputStream(new byte[] {1}));
        storage.store("1-174-s", "b.png", new ByteArrayInputStream(new byte[] {2}));

        storage.deleteFor("1-174-s");
        storage.deleteFor("1-999-never-uploaded-to");

        assertThat(uploadsDir.resolve("1-174-s")).doesNotExist();
    }
}
