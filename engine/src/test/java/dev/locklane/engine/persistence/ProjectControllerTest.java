package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectControllerTest {

    @Test
    void listReturnsEveryProject(@TempDir Path tmp) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        repository.create("foo", "url", tmp.resolve("foo"), Instant.now());
        ProjectController controller = controller(tmp, repository);

        assertThat(controller.list()).extracting(ProjectController.ProjectView::name).containsExactly("foo");
    }

    @Test
    void createWithABlankGitUrlIsABadRequest(@TempDir Path tmp) {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(new ProjectController.CreateProjectRequest("  ", "name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithANullGitUrlIsABadRequest(@TempDir Path tmp) {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(new ProjectController.CreateProjectRequest(null, "name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithAnUncloneableUrlStillReturnsCreatedWithAFailedProject(@TempDir Path tmp) {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response =
                controller.create(new ProjectController.CreateProjectRequest("/does/not/exist", "broken"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(body.name()).isEqualTo("broken");

        assertThat(controller.list()).extracting(ProjectController.ProjectView::status)
                .containsExactly(ProjectStatus.FAILED.name());
    }

    @Test
    void retryOnAnUnknownProjectIsNotFound(@TempDir Path tmp) {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<ProjectController.ProjectView> response = controller.retry(999);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRemovesAKnownProjectAndIsNoContent(@TempDir Path tmp) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<Void> response = controller.delete(created.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.list()).isEmpty();
    }

    @Test
    void deleteOnAnUnknownProjectIsNotFound(@TempDir Path tmp) {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        assertThat(controller.delete(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static ProjectController controller(Path tmp, ProjectRepository repository) {
        ProjectCheckoutService checkoutService =
                new ProjectCheckoutService(repository, tmp.resolve("workarea").toString(), Runnable::run);
        return new ProjectController(repository, checkoutService);
    }
}
