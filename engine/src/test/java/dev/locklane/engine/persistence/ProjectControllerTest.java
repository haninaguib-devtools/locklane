package dev.locklane.engine.persistence;

import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectControllerTest {

    @Test
    void listReturnsEveryProject(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        repository.create("foo", "url", tmp.resolve("foo"), Instant.now());
        ProjectController controller = controller(tmp, repository);

        assertThat(controller.list()).extracting(ProjectController.ProjectView::name).containsExactly("foo");
    }

    @Test
    void createWithABlankGitUrlIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(new ProjectController.CreateProjectRequest("  ", "name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithANullGitUrlIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(new ProjectController.CreateProjectRequest(null, "name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithAnUncloneableUrlStillReturnsCreatedWithAFailedProject(@TempDir Path tmp) throws IOException {
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
    void retryOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<ProjectController.ProjectView> response = controller.retry(999);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteRemovesAKnownProjectAndIsNoContent(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<Void> response = controller.delete(created.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.list()).isEmpty();
    }

    @Test
    void deleteOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        assertThat(controller.delete(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void settingAGithubTokenStoresItEncrypted(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response =
                controller.setGithubToken(created.id(), new ProjectController.SetGithubTokenRequest("ghp_secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String stored = repository.findGithubToken(created.id()).orElseThrow();
        assertThat(stored).isNotEqualTo("ghp_secret"); // encrypted, not plaintext
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        assertThat(cipher.decrypt(stored)).isEqualTo("ghp_secret");
    }

    @Test
    void settingAGithubTokenOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response =
                controller.setGithubToken(999, new ProjectController.SetGithubTokenRequest("ghp_secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void settingABlankGithubTokenIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response =
                controller.setGithubToken(created.id(), new ProjectController.SetGithubTokenRequest("  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findGithubToken(created.id())).isEmpty();
    }

    private static ProjectController controller(Path tmp, ProjectRepository repository) throws IOException {
        ProjectCheckoutService checkoutService =
                new ProjectCheckoutService(repository, tmp.resolve("workarea").toString(), Runnable::run);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        ProjectGhResources ghResources = new ProjectGhResources(repository, tokenCipher, (path, token) -> {
            throw new UnsupportedOperationException("not exercised by ProjectController's own tests");
        });
        return new ProjectController(repository, checkoutService, tokenCipher, ghResources);
    }
}
