package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

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
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository));

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
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository));

        assertThat(controller.consoles(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-console-0a1b2c3d");
    }

    @Test
    void returnsAnEmptyListWithNoOpenConsoles(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository));

        assertThat(controller.consoles(1, ALICE)).isEmpty();
    }

    @Test
    void revealFailsFastForAConsoleIdOutsideTheCallersProject(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("2-174-not-alices", dbDir.resolve("wt1"), Instant.now(), "alice");
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository));

        // Never reaches FileManagerLauncher at all -- the ownership check refuses
        // before any lookup of a working directory, exactly like an unknown id would.
        assertThat(controller.reveal(1, "2-174-not-alices", ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

    private static FileManagerLauncher launcher(WorktreeSessionRepository repository) {
        return new FileManagerLauncher(new SessionRegistry(repository));
    }
}
