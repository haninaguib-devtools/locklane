package dev.locklane.engine.github;

import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

        assertThat(controller.tree(999, false).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void treeWithFreshTrueBypassesTheCache(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        MutableGhClient client = new MutableGhClient(List.of(new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(root), tokenCipher, (path, token) -> client);
        IssueController controller = new IssueController(resources, mock(EventBroadcaster.class));
        // Warms the cache with the initial issue list.
        controller.tree(projectId, false);

        client.setIssues(List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", ""),
                new GhIssue(2, "Second", "OPEN", List.of(), "", "", "")));

        assertThat(controller.tree(projectId, false).getBody()).extracting(TreeNode::number).containsExactly(1);
        assertThat(controller.tree(projectId, true).getBody()).extracting(TreeNode::number).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void treeWithFreshTrueBroadcastsWhenTheRefreshChangesTheCache(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        MutableGhClient client = new MutableGhClient(List.of(new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(root), tokenCipher, (path, token) -> client);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        IssueController controller = new IssueController(resources, broadcaster);
        // Warms the cache with the initial issue list.
        controller.tree(projectId, false);

        client.setIssues(List.of(
                new GhIssue(1, "First", "OPEN", List.of(), "", "", ""),
                new GhIssue(2, "Second", "OPEN", List.of(), "", "", "")));
        controller.tree(projectId, true);

        verify(broadcaster, times(1)).broadcast("issuesChanged", Map.of("projectId", projectId));
    }

    @Test
    void treeWithFreshTrueDoesNotBroadcastWhenTheRefreshFindsNoChange(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        FixedGhClient client = new FixedGhClient(List.of(new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(root), tokenCipher, (path, token) -> client);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        IssueController controller = new IssueController(resources, broadcaster);
        // Warms the cache with the initial issue list -- the forced refresh below fetches
        // the exact same issues, so GhIssueCache.refresh() reports no change.
        controller.tree(projectId, false);

        controller.tree(projectId, true);

        verifyNoInteractions(broadcaster);
    }

    private static long readyProject(Path root) {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(root);
        return repository.createReady("proj", "url", root.resolve("checkout"), "main", 1L, Instant.now()).id();
    }

    private static IssueController controller(Path root, List<GhIssue> issues) throws IOException {
        FixedGhClient fake = new FixedGhClient(issues);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(root), tokenCipher, (path, token) -> fake);
        return new IssueController(resources, mock(EventBroadcaster.class));
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

    /** Like {@link FixedGhClient}, but its issue list can change between calls (#140). */
    private static final class MutableGhClient implements GhClient {
        private List<GhIssue> issues;

        MutableGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        void setIssues(List<GhIssue> issues) {
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
