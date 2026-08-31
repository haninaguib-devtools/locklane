package dev.locklane.engine.uploads;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.WorktreeSessionAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SessionUploadControllerTest {

    private static final Principal ALICE = () -> "alice";

    @Test
    void storesAnOwnersUploadUnderTheSessionsFolderAndReturnsTheAbsolutePath(@TempDir Path dbDir, @TempDir Path uploadsDir) {
        createProject(dbDir, "alice"); // project 1
        SessionUploadController controller = controller(dbDir, uploadsDir, 1024);

        var response = controller.upload("1-174-rename-toggle",
                new MockMultipartFile("file", "shot.png", "image/png", new byte[] {1, 2, 3}), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Path stored = Path.of(response.getBody().get("path"));
        assertThat(stored).isAbsolute();
        assertThat(stored.getParent()).isEqualTo(uploadsDir.resolve("1-174-rename-toggle"));
        assertThat(stored).hasBinaryContent(new byte[] {1, 2, 3});
    }

    @Test
    void anotherProjectsSessionIsNotFoundAndNothingIsWritten(@TempDir Path dbDir, @TempDir Path uploadsDir) {
        createProject(dbDir, "bob"); // project 1, owned by bob -- not alice
        SessionUploadController controller = controller(dbDir, uploadsDir, 1024);

        var response = controller.upload("1-174-bobs-session",
                new MockMultipartFile("file", "shot.png", "image/png", new byte[] {1}), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(uploadsDir.resolve("1-174-bobs-session")).doesNotExist();
    }

    @Test
    void anUploadOverTheCapIsRefusedWithPayloadTooLarge(@TempDir Path dbDir, @TempDir Path uploadsDir) {
        createProject(dbDir, "alice"); // project 1
        SessionUploadController controller = controller(dbDir, uploadsDir, 4);

        var response = controller.upload("1-174-rename-toggle",
                new MockMultipartFile("file", "shot.png", "image/png", new byte[] {1, 2, 3, 4, 5}), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("error")).contains("upload limit");
        assertThat(uploadsDir.resolve("1-174-rename-toggle")).doesNotExist();
    }

    @Test
    void anEmptyUploadIsRefused(@TempDir Path dbDir, @TempDir Path uploadsDir) {
        createProject(dbDir, "alice"); // project 1
        SessionUploadController controller = controller(dbDir, uploadsDir, 1024);

        var response = controller.upload("1-174-rename-toggle",
                new MockMultipartFile("file", "shot.png", "image/png", new byte[0]), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploadsDir.resolve("1-174-rename-toggle")).doesNotExist();
    }

    @Test
    void aRepeatedFilenameIsUniquifiedRatherThanOverwritten(@TempDir Path dbDir, @TempDir Path uploadsDir) throws Exception {
        createProject(dbDir, "alice"); // project 1
        SessionUploadController controller = controller(dbDir, uploadsDir, 1024);

        var first = controller.upload("1-174-rename-toggle",
                new MockMultipartFile("file", "shot.png", "image/png", new byte[] {1}), ALICE);
        var second = controller.upload("1-174-rename-toggle",
                new MockMultipartFile("file", "shot.png", "image/png", new byte[] {2}), ALICE);

        Path firstStored = Path.of(first.getBody().get("path"));
        Path secondStored = Path.of(second.getBody().get("path"));
        assertThat(secondStored).isNotEqualTo(firstStored);
        assertThat(Files.readAllBytes(firstStored)).containsExactly(1);
        assertThat(Files.readAllBytes(secondStored)).containsExactly(2);
    }

    @Test
    void theServletContainersOwnSizeRejectionMapsToTheSame413(@TempDir Path dbDir, @TempDir Path uploadsDir) {
        SessionUploadController controller = controller(dbDir, uploadsDir, 4);

        var response = controller.maxUploadSizeExceeded();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("error")).contains("upload limit");
    }

    /** A real project row (id 1, the first ever created in {@code dbDir}) owned by {@code ownerUsername}'s account. */
    private static void createProject(Path dbDir, String ownerUsername) {
        UserRecord owner = TestSqliteDatabases.newUserRepository(dbDir).create(ownerUsername, "bcrypt-hash", Instant.now());
        TestSqliteDatabases.newProjectRepository(dbDir).createReady("proj-" + ownerUsername, "url",
                dbDir.resolve("work-" + ownerUsername), "main", owner.id(), Instant.now());
    }

    private static SessionUploadController controller(Path dbDir, Path uploadsDir, long maxFileBytes) {
        WorktreeSessionAuthorization authorization = new WorktreeSessionAuthorization(
                TestSqliteDatabases.newProjectRepository(dbDir), TestSqliteDatabases.newUserRepository(dbDir));
        return new SessionUploadController(new SessionUploadStorage(uploadsDir.toString(), maxFileBytes), authorization);
    }
}
