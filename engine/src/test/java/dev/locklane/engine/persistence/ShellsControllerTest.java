package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShellsControllerTest {

    private static final Principal ALICE = () -> "alice";
    private static final Principal BOB = () -> "bob";

    @Test
    void openingOnAnUnknownProjectIsNotFound(@TempDir Path dbDir) {
        ShellsController controller = controller(dbDir, TestSqliteDatabases.newProjectRepository(dbDir));

        assertThat(controller.open(999, new ShellsController.OpenShellRequest(7, dbDir.toString()), ALICE)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openingWithNoDirectoryIsBadRequest(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ShellsController controller = controller(dbDir, projectRepository);

        assertThat(controller.open(projectId, new ShellsController.OpenShellRequest(7, " "), ALICE)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.open(projectId, new ShellsController.OpenShellRequest(7, null), ALICE)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void openingReturnsTheSessionIdAndDirectoryToAttachWith(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ShellsController controller = controller(dbDir, projectRepository);

        var response = controller.open(projectId,
                new ShellsController.OpenShellRequest(7, dbDir.resolve("proj-7").toString()), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("sessionId")).matches("^" + projectId + "-shell-7-[0-9a-f]{8}$");
        assertThat(response.getBody().get("workingDirectory")).isEqualTo(dbDir.resolve("proj-7").toString());
    }

    @Test
    void listingCarriesTheGroupingFieldsAndOnlyTheCallersShells(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ShellsController controller = controller(dbDir, projectRepository);
        controller.open(projectId, new ShellsController.OpenShellRequest(7, dbDir.resolve("proj-7").toString()),
                ALICE);
        controller.open(projectId, new ShellsController.OpenShellRequest(null, dbDir.resolve("work").toString()),
                ALICE);

        List<ShellsController.OpenShellView> alicesShells = controller.shells(ALICE);

        assertThat(alicesShells).hasSize(2);
        assertThat(alicesShells).filteredOn(row -> !row.mainCheckout()).singleElement()
                .satisfies(row -> {
                    assertThat(row.projectId()).isEqualTo(projectId);
                    assertThat(row.issueNumber()).isEqualTo(7);
                });
        assertThat(alicesShells).filteredOn(ShellsController.OpenShellView::mainCheckout).singleElement()
                .satisfies(row -> assertThat(row.issueNumber()).isNull());
        assertThat(controller.shells(BOB)).isEmpty();
    }

    private static ShellsController controller(Path dbDir, ProjectRepository projectRepository) {
        return new ShellsController(new ShellSessionService(projectRepository,
                TestSqliteDatabases.newRepository(dbDir),
                new WorktreeSessionAuthorization(projectRepository, TestSqliteDatabases.newUserRepository(dbDir))));
    }

    private static long createUser(Path dbDir, String username) {
        return TestSqliteDatabases.newUserRepository(dbDir).create(username, "bcrypt-hash", Instant.now()).id();
    }
}
