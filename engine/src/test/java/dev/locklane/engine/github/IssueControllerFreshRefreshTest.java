package dev.locklane.engine.github;

import dev.locklane.engine.github.GhIssueCacheIncrementalTest.FakeRepo;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static dev.locklane.engine.github.GhIssueCacheIncrementalTest.issue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * #995: the sidenav's refresh button ({@code tree?fresh=true}) makes the same cheap
 * refresh the scheduled poll does — one conditional probe when nothing changed, only
 * the changed issues otherwise — instead of re-fetching everything.
 */
class IssueControllerFreshRefreshTest {

    @Test
    void aFreshTreeOnAWarmCacheWithNothingChangedMakesOnlyTheConditionalProbe(@TempDir Path root) throws IOException {
        FakeRepo repo = new FakeRepo();
        repo.issues.add(issue(1, "One", "2026-09-01T00:00:00Z"));
        Fixture fixture = new Fixture(root, repo);
        fixture.resources.refreshAll(); // the poll warms the cache
        repo.calls.clear();

        fixture.controller.tree(fixture.projectId, true);

        assertThat(repo.calls).containsExactly("probe W/\"1\"");
    }

    @Test
    void anIssueClosedOnGitHubShowsAsClosedAfterOneFreshTree(@TempDir Path root) throws IOException {
        FakeRepo repo = new FakeRepo();
        repo.issues.add(issue(1, "One", "2026-09-01T00:00:00Z"));
        repo.issues.add(issue(2, "Two", "2026-09-02T00:00:00Z"));
        Fixture fixture = new Fixture(root, repo);
        fixture.resources.refreshAll();
        repo.calls.clear();

        GhIssue open = repo.issues.get(0);
        repo.issues.set(0, new GhIssue(open.number(), open.title(), "CLOSED", open.labels(), open.body(),
                open.createdAt(), "2026-09-05T00:00:00Z"));
        repo.version++;
        TreeResponse tree = fixture.controller.tree(fixture.projectId, true).getBody();

        assertThat(tree.nodes()).filteredOn(node -> node.number() == 1)
                .extracting(TreeNode::state).containsExactly("CLOSED");
        assertThat(repo.calls).doesNotContain("issues", "pullRequests");
    }

    private static final class Fixture {
        final ProjectGhResources resources;
        final IssueController controller;
        final long projectId;

        Fixture(Path root, FakeRepo repo) throws IOException {
            ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
            projectId = projectRepository.createReady("myproj", "url", root.resolve("myproj"), "main", 1L,
                    Instant.now()).id();
            resources = new ProjectGhResources(projectRepository, TestSqliteDatabases.newGhAccountRepository(root),
                    new TokenCipher(new EncryptionKeyProvider(root.toString())), (path, token) -> repo);
            controller = new IssueController(resources, mock(EventBroadcaster.class));
        }
    }
}
