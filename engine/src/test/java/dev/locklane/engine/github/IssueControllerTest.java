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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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

        assertThat(controller.tree(projectId, false).getBody().nodes()).extracting(TreeNode::number).containsExactly(1);
        assertThat(controller.tree(projectId, true).getBody().nodes()).extracting(TreeNode::number).containsExactlyInAnyOrder(1, 2);
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

    @Test
    void treeCarriesTheFailureWhenTheForcedRefreshThrowsAndClearsItOnTheNextSuccess(@TempDir Path root) throws IOException {
        // #619: a refresh that fails still answers 200 with the cached tree -- the
        // response has to say so, or the caller cannot tell stale from up to date.
        long projectId = readyProject(root);
        MutableGhClient client = new MutableGhClient(List.of(new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(root), tokenCipher, (path, token) -> client);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        IssueController controller = new IssueController(resources, broadcaster);
        controller.tree(projectId, false); // warms the cache

        client.failWith("gh exited 1: HTTP 401: Bad credentials");
        TreeResponse failed = controller.tree(projectId, true).getBody();

        assertThat(failed.nodes()).extracting(TreeNode::number).containsExactly(1);
        assertThat(failed.github().failing()).isTrue();
        assertThat(failed.github().failure()).isEqualTo("gh exited 1: HTTP 401: Bad credentials");
        assertThat(failed.github().lastSuccessAt()).isNotNull();
        verify(broadcaster).broadcast(eq("githubRefreshStatus"), argThat((Map<String, ?> fields) ->
                fields.get("projectId").equals(projectId) && fields.get("failing").equals(true)
                        && fields.get("failure").equals("gh exited 1: HTTP 401: Bad credentials")));

        client.failWith(null);
        TreeResponse recovered = controller.tree(projectId, true).getBody();

        assertThat(recovered.github().failing()).isFalse();
        assertThat(recovered.github().failure()).isNull();
        verify(broadcaster).broadcast(eq("githubRefreshStatus"), argThat((Map<String, ?> fields) ->
                fields.get("projectId").equals(projectId) && fields.get("failing").equals(false)));
    }

    @Test
    void treeWithFreshTrueDoesNotBroadcastAStatusEventWhenTheOutcomeDidNotMove(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        MutableGhClient client = new MutableGhClient(List.of(new GhIssue(1, "First", "OPEN", List.of(), "", "", "")));
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(root), tokenCipher, (path, token) -> client);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        IssueController controller = new IssueController(resources, broadcaster);
        controller.tree(projectId, false);
        client.failWith("HTTP 401: Bad credentials");
        controller.tree(projectId, true); // first failure: broadcast once

        controller.tree(projectId, true); // still failing the same way: nothing new to say

        verify(broadcaster, times(1)).broadcast(eq("githubRefreshStatus"), any());
    }

    @Test
    void labelsReturnsWhatTheClientReports(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        LabelGhClient client = new LabelGhClient(List.of(), List.of(new GhLabel("bug", "d73a4a")));
        IssueController controller = controllerWith(root, client);

        assertThat(controller.labels(projectId).getBody()).containsExactly(new GhLabel("bug", "d73a4a"));
    }

    @Test
    void labelsIsNotFoundForAnUnknownProject(@TempDir Path root) throws IOException {
        readyProject(root);
        IssueController controller = controllerWith(root, new LabelGhClient(List.of(), List.of()));

        assertThat(controller.labels(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateLabelsAppliesTheChangeAndReturnsTheUpdatedIssue(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        LabelGhClient client = new LabelGhClient(
                List.of(new GhIssue(1, "First", "OPEN", List.of("wontfix"), "", "", "")), List.of());
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        IssueController controller = controllerWith(root, client, broadcaster);

        ResponseEntity<GhIssue> response = controller.updateLabels(projectId, 1,
                new LabelUpdateRequest(List.of("bug"), List.of("wontfix")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().labels()).containsExactly("bug");
        assertThat(client.lastAdd).containsExactly("bug");
        assertThat(client.lastRemove).containsExactly("wontfix");
        verify(broadcaster).broadcast("issuesChanged", Map.of("projectId", projectId));
    }

    @Test
    void updateLabelsIsNotFoundForAnUnknownIssue(@TempDir Path root) throws IOException {
        long projectId = readyProject(root);
        LabelGhClient client = new LabelGhClient(List.of(), List.of());
        IssueController controller = controllerWith(root, client);

        ResponseEntity<GhIssue> response = controller.updateLabels(projectId, 404,
                new LabelUpdateRequest(List.of("bug"), List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(client.lastAdd).isNull();
    }

    @Test
    void updateLabelsIsNotFoundForAnUnknownProject(@TempDir Path root) throws IOException {
        readyProject(root);
        IssueController controller = controllerWith(root, new LabelGhClient(List.of(), List.of()));

        ResponseEntity<GhIssue> response = controller.updateLabels(999, 1, new LabelUpdateRequest(List.of(), List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static IssueController controllerWith(Path root, GhClient client) throws IOException {
        return controllerWith(root, client, mock(EventBroadcaster.class));
    }

    private static IssueController controllerWith(Path root, GhClient client, EventBroadcaster broadcaster) throws IOException {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(root.toString()));
        ProjectGhResources resources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(root), tokenCipher, (path, token) -> client);
        return new IssueController(resources, broadcaster);
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

    /** Like {@link FixedGhClient}, but its issue list can change between calls (#140), and it can be made to fail (#619). */
    private static final class MutableGhClient implements GhClient {
        private List<GhIssue> issues;
        private String failure;

        MutableGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        void setIssues(List<GhIssue> issues) {
            this.issues = issues;
        }

        /** Every call throws with this message until reset with {@code null}. */
        void failWith(String failure) {
            this.failure = failure;
        }

        @Override
        public List<GhIssue> issues() {
            if (failure != null) {
                throw new GhUnavailableException(failure, null);
            }
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

    /** Serves fixed repo labels and applies an add/remove call to its own issue list, so a refresh sees the change (#962). */
    private static final class LabelGhClient implements GhClient {
        private List<GhIssue> issues;
        private final List<GhLabel> labels;
        private List<String> lastAdd;
        private List<String> lastRemove;

        LabelGhClient(List<GhIssue> issues, List<GhLabel> labels) {
            this.issues = issues;
            this.labels = labels;
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

        @Override
        public List<GhLabel> labels() {
            return labels;
        }

        @Override
        public void updateIssueLabels(int number, List<String> add, List<String> remove) {
            lastAdd = add;
            lastRemove = remove;
            issues = issues.stream()
                    .map(issue -> issue.number() == number ? withLabels(issue, add, remove) : issue)
                    .toList();
        }

        private static GhIssue withLabels(GhIssue issue, List<String> add, List<String> remove) {
            List<String> updated = new ArrayList<>(issue.labels());
            updated.removeAll(remove);
            for (String label : add) {
                if (!updated.contains(label)) {
                    updated.add(label);
                }
            }
            return new GhIssue(issue.number(), issue.title(), issue.state(), updated, issue.body(),
                    issue.createdAt(), issue.updatedAt(), issue.parent(), issue.author());
        }
    }
}
