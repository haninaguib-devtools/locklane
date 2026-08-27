package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConsolesControllerTest {

    private static final Principal ALICE = () -> "alice";

    @Test
    void returnsEveryVisibleConsoleAcrossIssuesInTheProject(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-175-bobs-session", dbDir.resolve("wt2"), now, "bob");
        repository.recordAttach("2-174-other-project", dbDir.resolve("wt3"), now, "alice");
        ConsolesController controller = new ConsolesController(new IssueWorktreeService(repository));

        assertThat(controller.consoles(1, ALICE)).containsExactly("1-174-rename-toggle");
    }

    @Test
    void includesTheProjectsOwnConsolesAlongsideItsIssues(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "alice");
        ConsolesController controller = new ConsolesController(new IssueWorktreeService(repository));

        assertThat(controller.consoles(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-console-0a1b2c3d");
    }

    @Test
    void returnsAnEmptyListWithNoOpenConsoles(@TempDir Path dbDir) {
        ConsolesController controller = new ConsolesController(
                new IssueWorktreeService(TestSqliteDatabases.newRepository(dbDir)));

        assertThat(controller.consoles(1, ALICE)).isEmpty();
    }
}
