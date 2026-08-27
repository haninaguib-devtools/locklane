package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectConsoleControllerTest {

    private static final Principal ALICE = () -> "alice";
    private static final Principal BOB = () -> "bob";

    @Test
    void startingOnAnUnknownProjectIsNotFound(@TempDir Path dbDir) {
        ProjectConsoleController controller = controller(dbDir, TestSqliteDatabases.newProjectRepository(dbDir),
                new SessionRegistry(TestSqliteDatabases.newRepository(dbDir)));

        assertThat(controller.start(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startingOnAReadyProjectReturnsItsSessionIdAndWorkingDirectory(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        Path workarea = dbDir.resolve("work");
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", Instant.now()).id();
        ProjectConsoleController controller =
                controller(dbDir, projectRepository, new SessionRegistry(TestSqliteDatabases.newRepository(dbDir)));

        var response = controller.start(projectId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("sessionId", projectId + "-console")
                .containsEntry("workingDirectory", workarea.toString());
    }

    @Test
    void discoveringBeforeAnyAttachIsNotFound(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        ProjectConsoleController controller =
                controller(dbDir, projectRepository, new SessionRegistry(TestSqliteDatabases.newRepository(dbDir)));

        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void discoveringAfterAnAttachReturnsItToItsOwner(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        sessionRegistry.attach(projectId + "-console", dbDir, null, "alice");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRegistry);

        var response = controller.get(projectId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("sessionId", projectId + "-console");
    }

    @Test
    void discoveringAnotherUsersSessionIsNotFound(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        sessionRegistry.attach(projectId + "-console", dbDir, null, "bob");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRegistry);

        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void closingTheOwnersSessionSucceeds(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        sessionRegistry.attach(projectId + "-console", dbDir, null, "alice");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRegistry);

        var response = controller.close(projectId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void closingAnotherUsersSessionIsNotFoundAndLeavesItRunning(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(TestSqliteDatabases.newRepository(dbDir));
        sessionRegistry.attach(projectId + "-console", dbDir, null, "bob");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRegistry);

        assertThat(controller.close(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.get(projectId, BOB).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static ProjectConsoleController controller(Path dbDir, ProjectRepository projectRepository,
            SessionRegistry sessionRegistry) {
        return new ProjectConsoleController(new ProjectConsoleService(projectRepository, tokenCipher(dbDir), sessionRegistry));
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
