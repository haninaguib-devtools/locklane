package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorktreeControllerTest {

    @Test
    void returnsTheWorktreeIdsForAnIssue(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"));
        WorktreeController controller = new WorktreeController(new IssueWorktreeService(repository));

        assertThat(controller.worktrees(174)).containsExactly("174-rename-toggle");
    }

    @Test
    void returnsAnEmptyListForAnIssueWithNoWorktrees(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        WorktreeController controller = new WorktreeController(new IssueWorktreeService(repository));

        assertThat(controller.worktrees(1)).isEmpty();
    }
}
