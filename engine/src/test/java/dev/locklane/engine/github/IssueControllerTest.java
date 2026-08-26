package dev.locklane.engine.github;

import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IssueControllerTest {

    @Test
    void listReturnsWhatTheCacheHolds(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        IssueController controller = controller(root, List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));

        assertThat(controller.list(projectId).getBody()).extracting(GhIssue::number).containsExactly(1);
    }

    @Test
    void listIsNotFoundForAnUnknownProject(@TempDir Path root) throws IOException {
        readyProject(root);
        IssueController controller = controller(root, List.of());

        assertThat(controller.list(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detailReturnsTheMatchingIssue(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        GhIssue two = new GhIssue(2, "Second", "OPEN", List.of(), "body", "", "");
        IssueController controller = controller(root, List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", ""), two));

        ResponseEntity<GhIssue> response = controller.detail(projectId, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(two);
    }

    @Test
    void detailIsNotFoundForAnUnknownIssue(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        IssueController controller = controller(root, List.of());

        ResponseEntity<GhIssue> response = controller.detail(projectId, 404);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void detailIsNotFoundForAnUnknownProject(@TempDir Path root) throws IOException {
        readyProject(root);
        IssueController controller = controller(root, List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));

        assertThat(controller.detail(999, 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void issueDetailReturnsFlowStateForAKnownIssue(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        IssueController controller = controller(root, List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));

        ResponseEntity<IssueDetail> response = controller.issueDetail(projectId, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().number()).isEqualTo(1);
    }

    @Test
    void issueDetailIsNotFoundForAnUnknownIssue(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        IssueController controller = controller(root, List.of());

        ResponseEntity<IssueDetail> response = controller.issueDetail(projectId, 404);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void treeIsNotFoundForAnUnknownProject(@TempDir Path root) throws IOException {
        readyProject(root);
        IssueController controller = controller(root, List.of());

        assertThat(controller.tree(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static long readyProject(Path root) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(root);
        return repository.createReady("proj", "url", root.resolve("checkout"), "main", Instant.now()).id();
    }

    private static IssueController controller(Path root, List<GhIssue> issues) throws IOException {
        FixedGhClient fake = new FixedGhClient(issues);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository, tokenCipher, (path, token) -> fake);
        return new IssueController(resources);
    }

    private static final class FixedGhClient implements GhClient {
        private final List<GhIssue> issues;

        FixedGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        @Override
        public List<GhIssue> issues() {
            return issues;
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
