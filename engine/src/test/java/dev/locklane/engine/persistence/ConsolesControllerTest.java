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
    void returnsEveryVisibleConsoleAcrossIssues(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("175-bobs-session", dbDir.resolve("wt2"), now, "bob");
        ConsolesController controller = new ConsolesController(new IssueWorktreeService(repository));

        assertThat(controller.consoles(ALICE)).containsExactly("174-rename-toggle");
    }

    @Test
    void returnsAnEmptyListWithNoOpenConsoles(@TempDir Path dbDir) {
        ConsolesController controller = new ConsolesController(
                new IssueWorktreeService(TestSqliteDatabases.newRepository(dbDir)));

        assertThat(controller.consoles(ALICE)).isEmpty();
    }
}
