package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhClient;
import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhIssueCache;
import dev.locklane.engine.github.GhPullRequest;
import dev.locklane.engine.github.GhPullRequestDetail;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeControllerTest {

    private static final Principal ALICE = () -> "alice";
    private static final Principal BOB = () -> "bob";

    @Test
    void returnsTheWorktreeIdsForAnIssue(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "alice");
        WorktreeController controller = controller(dbDir, repository, List.of());

        assertThat(controller.worktrees(174, ALICE)).containsExactly("174-rename-toggle");
    }

    @Test
    void returnsAnEmptyListForAnIssueWithNoWorktrees(@TempDir Path dbDir) {
        WorktreeController controller = controller(dbDir, TestSqliteDatabases.newRepository(dbDir), List.of());

        assertThat(controller.worktrees(1, ALICE)).isEmpty();
    }

    @Test
    void doesNotReturnAnotherUsersWorktree(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("174-bobs-session", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "bob");
        WorktreeController controller = controller(dbDir, repository, List.of());

        assertThat(controller.worktrees(174, ALICE)).isEmpty();
    }

    @Test
    void closingASessionRemovesItFromTheWorktreeListAndStopsTheRegistry(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "alice");
        SessionRegistry sessionRegistry = new SessionRegistry(repository);
        WorktreeController controller = controller(dbDir, repository, sessionRegistry, List.of());

        var response = controller.closeSession(174, "174-rename-toggle", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.worktrees(174, ALICE)).isEmpty();
        assertThat(repository.find("174-rename-toggle")).isEmpty();
    }

    @Test
    void closingAnotherUsersSessionIsNotFound(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("174-bobs-session", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "bob");
        WorktreeController controller = controller(dbDir, repository, List.of());

        var response = controller.closeSession(174, "174-bobs-session", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.worktrees(174, BOB)).containsExactly("174-bobs-session");
    }

    @Test
    void closingAnUnknownSessionIsNotFound(@TempDir Path dbDir) {
        WorktreeController controller = controller(dbDir, TestSqliteDatabases.newRepository(dbDir), List.of());

        var response = controller.closeSession(174, "174-never-existed", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static WorktreeController controller(Path dbDir, WorktreeSessionRepository repository, List<GhIssue> issues) {
        return controller(dbDir, repository, new SessionRegistry(repository), issues);
    }

    private static WorktreeController controller(Path dbDir, WorktreeSessionRepository repository,
            SessionRegistry sessionRegistry, List<GhIssue> issues) {
        IssueWorktreeService worktreeService = new IssueWorktreeService(repository);
        GhIssueCache cache = new GhIssueCache(new FixedGhClient(issues));
        WorktreeCreationService creationService =
                new WorktreeCreationService(cache, worktreeService, dbDir.toString());
        return new WorktreeController(worktreeService, creationService, sessionRegistry);
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
