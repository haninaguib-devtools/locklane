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
    void returnsEveryVisibleConsoleAcrossIssuesInTheProjectRegardlessOfWhoAttached(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        // #242: visibility is derived from the project alice owns, not from who
        // attached -- bob attaching to a session in project 1 doesn't move it out
        // of alice's view.
        repository.recordAttach("1-175-bobs-session", dbDir.resolve("wt2"), now, "bob");
        repository.recordAttach("2-174-other-project", dbDir.resolve("wt3"), now, "alice");
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository));

        assertThat(controller.consoles(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-175-bobs-session");
    }

    @Test
    void includesTheProjectsOwnConsolesAlongsideItsIssues(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "alice");
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository));

        assertThat(controller.consoles(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-console-0a1b2c3d");
    }

    @Test
    void returnsAnEmptyListWithNoOpenConsoles(@TempDir Path dbDir) {
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, TestSqliteDatabases.newRepository(dbDir)));

        assertThat(controller.consoles(1, ALICE)).isEmpty();
    }

    /** A real project row owned by {@code ownerUsername}'s (freshly created) account. */
    private static void createProject(Path dbDir, String ownerUsername) {
        UserRecord owner = TestSqliteDatabases.newUserRepository(dbDir).create(ownerUsername, "bcrypt-hash", Instant.now());
        TestSqliteDatabases.newProjectRepository(dbDir).createReady("proj-" + ownerUsername, "url",
                dbDir.resolve("work-" + ownerUsername), "main", owner.id(), Instant.now());
    }

    private static IssueWorktreeService worktreeService(Path dbDir, WorktreeSessionRepository repository) {
        WorktreeSessionAuthorization authorization = new WorktreeSessionAuthorization(
                TestSqliteDatabases.newProjectRepository(dbDir), TestSqliteDatabases.newUserRepository(dbDir));
        return new IssueWorktreeService(repository, authorization);
    }
}
