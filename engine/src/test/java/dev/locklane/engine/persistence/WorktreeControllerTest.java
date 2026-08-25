package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhClient;
import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhIssueCache;
import dev.locklane.engine.github.GhPullRequest;
import dev.locklane.engine.github.GhPullRequestDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeControllerTest {

    @Test
    void returnsTheWorktreeIdsForAnIssue(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"));
        WorktreeController controller = controller(dbDir, repository, List.of());

        assertThat(controller.worktrees(174)).containsExactly("174-rename-toggle");
    }

    @Test
    void returnsAnEmptyListForAnIssueWithNoWorktrees(@TempDir Path dbDir) {
        WorktreeController controller = controller(dbDir, TestSqliteDatabases.newRepository(dbDir), List.of());

        assertThat(controller.worktrees(1)).isEmpty();
    }

    private static WorktreeController controller(Path dbDir, WorktreeSessionRepository repository, List<GhIssue> issues) {
        IssueWorktreeService worktreeService = new IssueWorktreeService(repository);
        GhIssueCache cache = new GhIssueCache(new FixedGhClient(issues));
        WorktreeCreationService creationService =
                new WorktreeCreationService(cache, worktreeService, dbDir.toString());
        return new WorktreeController(worktreeService, creationService);
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
