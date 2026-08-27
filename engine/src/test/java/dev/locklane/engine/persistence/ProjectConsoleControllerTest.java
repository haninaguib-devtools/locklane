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
import static org.assertj.core.api.Assertions.tuple;

class ProjectConsoleControllerTest {

    private static final Principal ALICE = () -> "alice";
    private static final Principal BOB = () -> "bob";
    private static final Instant EARLIER = Instant.parse("2026-08-25T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-25T13:00:00Z");

    @Test
    void startingOnAnUnknownProjectIsNotFound(@TempDir Path dbDir) {
        ProjectConsoleController controller = controller(dbDir, TestSqliteDatabases.newProjectRepository(dbDir),
                TestSqliteDatabases.newRepository(dbDir));

        assertThat(controller.start(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startingOnAReadyProjectMintsAFreshSessionIdEveryCall(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        Path workarea = dbDir.resolve("work");
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", Instant.now()).id();
        ProjectConsoleController controller =
                controller(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        var first = controller.start(projectId);
        var second = controller.start(projectId);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("sessionId")).matches("^" + projectId + "-console-[0-9a-f]{8}$");
        assertThat(first.getBody()).containsEntry("workingDirectory", workarea.toString());
        assertThat(second.getBody().get("sessionId")).isNotEqualTo(first.getBody().get("sessionId"));
    }

    @Test
    void discoveringBeforeAnyAttachIsNotFound(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        ProjectConsoleController controller =
                controller(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void discoveringAfterAnAttachReturnsItToItsOwner(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRepository);

        var response = controller.get(projectId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("sessionId", projectId + "-console-0a1b2c3d");
    }

    @Test
    void discoveringAnotherUsersSessionIsNotFound(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listingOpenConsolesReturnsTheCallersOldestFirst(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-cccccccc", dbDir, EARLIER, "bob");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.sessions(projectId, ALICE))
                .extracting(ProjectConsoleController.OpenConsoleView::sessionId,
                        ProjectConsoleController.OpenConsoleView::createdAt)
                .containsExactly(
                        tuple(projectId + "-console-aaaaaaaa", EARLIER.toString()),
                        tuple(projectId + "-console-bbbbbbbb", LATER.toString()));
    }

    @Test
    void listingAProjectWithNoOpenConsoleIsEmpty(@TempDir Path dbDir) {
        ProjectConsoleController controller = controller(dbDir, TestSqliteDatabases.newProjectRepository(dbDir),
                TestSqliteDatabases.newRepository(dbDir));

        assertThat(controller.sessions(999, ALICE)).isEmpty();
    }

    @Test
    void closingTheOwnersSessionSucceeds(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRepository);

        var response = controller.close(projectId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void closingAnotherUsersSessionIsNotFoundAndLeavesItRunning(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.close(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.get(projectId, BOB).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void closingOneConsoleByIdLeavesItsSiblingsOpen(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRepository);

        var response = controller.close(projectId, projectId + "-console-aaaaaaaa", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.sessions(projectId, ALICE))
                .extracting(ProjectConsoleController.OpenConsoleView::sessionId)
                .containsExactly(projectId + "-console-bbbbbbbb");
    }

    @Test
    void closingByIdRefusesAnIdOutsideTheProjectsFamilyOrAnotherUsers(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-174-some-worktree", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, EARLIER, "bob");
        ProjectConsoleController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.close(projectId, projectId + "-174-some-worktree", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.close(projectId, projectId + "-console-bbbbbbbb", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sessionRepository.findAll()).hasSize(2);
    }

    private static ProjectConsoleController controller(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository) {
        return new ProjectConsoleController(new ProjectConsoleService(projectRepository, tokenCipher(dbDir),
                new SessionRegistry(sessionRepository), sessionRepository));
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
