package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhClient;
import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhPullRequest;
import dev.locklane.engine.github.GhPullRequestDetail;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
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
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "alice");
        WorktreeController controller = controller(dbDir, repository, List.of());

        assertThat(controller.worktrees(1, 174, ALICE)).containsExactly("1-174-rename-toggle");
    }

    @Test
    void returnsAnEmptyListForAnIssueWithNoWorktrees(@TempDir Path dbDir) {
        WorktreeController controller = controller(dbDir, TestSqliteDatabases.newRepository(dbDir), List.of());

        assertThat(controller.worktrees(1, 1, ALICE)).isEmpty();
    }

    @Test
    void doesNotReturnAnotherProjectsWorktree(@TempDir Path dbDir) {
        createProject(dbDir, "bob"); // project 1, owned by bob -- not alice
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "bob");
        WorktreeController controller = controller(dbDir, repository, List.of());

        assertThat(controller.worktrees(1, 174, ALICE)).isEmpty();
    }

    @Test
    void closingASessionRemovesItFromTheWorktreeListAndStopsTheRegistry(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "alice");
        SessionRegistry sessionRegistry = new SessionRegistry(repository);
        WorktreeController controller = controller(dbDir, repository, sessionRegistry, List.of());

        var response = controller.closeSession(1, 174, "1-174-rename-toggle", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.worktrees(1, 174, ALICE)).isEmpty();
        assertThat(repository.find("1-174-rename-toggle")).isEmpty();
    }

    @Test
    void closingAnotherProjectsSessionIsNotFound(@TempDir Path dbDir) {
        createProject(dbDir, "bob"); // project 1, owned by bob -- not alice
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "bob");
        WorktreeController controller = controller(dbDir, repository, List.of());

        var response = controller.closeSession(1, 174, "1-174-bobs-session", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.worktrees(1, 174, BOB)).containsExactly("1-174-bobs-session");
    }

    @Test
    void closingAnUnknownSessionIsNotFound(@TempDir Path dbDir) {
        WorktreeController controller = controller(dbDir, TestSqliteDatabases.newRepository(dbDir), List.of());

        var response = controller.closeSession(1, 174, "1-174-never-existed", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startingASessionOnAnUnknownProjectIsNotFound(@TempDir Path dbDir) {
        GhIssue issue = new GhIssue(174, "Rename toggle", "OPEN", List.of(), "", "", "");
        WorktreeController controller = controller(dbDir, TestSqliteDatabases.newRepository(dbDir), List.of(issue));

        var response = controller.startSession(999, 174, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listsTheCapturedResumeSessionsForAnIssue(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        ConsoleResumeSessionRepository resumeRepository =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record("1-174-rename-toggle", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        WorktreeController controller = controller(dbDir, repository, resumeRepository, List.of());

        assertThat(controller.resumeSessions(1, 174, ALICE)).containsExactly(
                new WorktreeController.ResumeSessionView("1-174-rename-toggle", "claude",
                        "aaaaaaaa-0000-0000-0000-000000000000", "2026-08-25T12:00:00Z", null));
        assertThat(controller.resumeSessions(1, 175, ALICE)).isEmpty();
    }

    @Test
    void resumeSessionsExcludesALegacyMainConsolesConversation(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        ConsoleResumeSessionRepository resumeRepository =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record("1-174-main-a1b2c3d4", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        resumeRepository.record("1-174-rename-toggle", "claude", "bbbbbbbb-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        WorktreeController controller = controller(dbDir, repository, resumeRepository, List.of());

        // #341: a legacy main-checkout console's conversation can never be
        // resumed (there is no worktree that contains it), so it is left off the
        // list rather than shown as a dead end.
        assertThat(controller.resumeSessions(1, 174, ALICE)).extracting(WorktreeController.ResumeSessionView::worktreeId)
                .containsExactly("1-174-rename-toggle");
    }

    @Test
    void reopeningALegacyMainConsolesConversationIsNotFound(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        ConsoleResumeSessionRepository resumeRepository =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record("1-174-main-a1b2c3d4", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        WorktreeController controller = controller(dbDir, repository, resumeRepository, List.of());

        var response = controller.reopenSession(1, 174, "1-174-main-a1b2c3d4", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reopeningAConsoleWithNoVisibleConversationIsNotFound(@TempDir Path dbDir) {
        createProject(dbDir, "bob"); // project 1, owned by bob -- not alice
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        ConsoleResumeSessionRepository resumeRepository =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        // Bob's console is open in his own project, so its conversation stays his (#242).
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "bob");
        resumeRepository.record("1-174-bobs-session", "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        WorktreeController controller = controller(dbDir, repository, resumeRepository, List.of());

        assertThat(controller.reopenSession(1, 174, "1-174-never-captured", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.reopenSession(1, 174, "1-174-bobs-session", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reopeningAVisibleConversationMintsAFreshSessionInItsDirectory(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        ConsoleResumeSessionRepository resumeRepository =
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long ownerId = TestSqliteDatabases.newUserRepository(dbDir).create("alice", "bcrypt-hash", Instant.now()).id();
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("workarea"), "main", ownerId, Instant.now()).id();
        String originalId = projectId + "-174-rename-toggle";
        repository.recordAttach(originalId, dbDir.resolve("wt"), Instant.parse("2026-08-25T12:00:00Z"), "alice");
        resumeRepository.record(originalId, "claude", "aaaaaaaa-0000-0000-0000-000000000000",
                Instant.parse("2026-08-25T12:00:00Z"));
        WorktreeController controller =
                controller(repository, resumeRepository, projectRepository, new SessionRegistry(repository), List.of(), dbDir);

        var response = controller.reopenSession(projectId, 174, originalId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("worktreeId")).startsWith(projectId + "-174-resume-");
        assertThat(response.getBody().get("workingDirectory")).isEqualTo(dbDir.resolve("wt").toString());
    }

    /** A real project row (id 1, the first ever created in {@code dbDir}) owned by {@code ownerUsername}'s account. */
    private static void createProject(Path dbDir, String ownerUsername) {
        UserRecord owner = TestSqliteDatabases.newUserRepository(dbDir).create(ownerUsername, "bcrypt-hash", Instant.now());
        TestSqliteDatabases.newProjectRepository(dbDir).createReady("proj-" + ownerUsername, "url",
                dbDir.resolve("work-" + ownerUsername), "main", owner.id(), Instant.now());
    }

    private static WorktreeController controller(Path dbDir, WorktreeSessionRepository repository, List<GhIssue> issues) {
        return controller(dbDir, repository, new SessionRegistry(repository), issues);
    }

    private static WorktreeController controller(Path dbDir, WorktreeSessionRepository repository,
            SessionRegistry sessionRegistry, List<GhIssue> issues) {
        return controller(repository, new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir)),
                TestSqliteDatabases.newProjectRepository(dbDir), sessionRegistry, issues, dbDir);
    }

    private static WorktreeController controller(Path dbDir, WorktreeSessionRepository repository,
            ConsoleResumeSessionRepository resumeRepository, List<GhIssue> issues) {
        return controller(repository, resumeRepository, TestSqliteDatabases.newProjectRepository(dbDir),
                new SessionRegistry(repository), issues, dbDir);
    }

    private static WorktreeController controller(WorktreeSessionRepository repository,
            ConsoleResumeSessionRepository resumeRepository, ProjectRepository projectRepository,
            SessionRegistry sessionRegistry, List<GhIssue> issues, Path dbDir) {
        WorktreeSessionAuthorization authorization =
                new WorktreeSessionAuthorization(projectRepository, TestSqliteDatabases.newUserRepository(dbDir));
        IssueWorktreeService worktreeService = new IssueWorktreeService(repository, resumeRepository, authorization);
        ProjectGhResources ghResources = new ProjectGhResources(projectRepository,
                TestSqliteDatabases.newGhAccountRepository(dbDir), tokenCipher(dbDir),
                (path, token) -> new FixedGhClient(issues));
        WorktreeCreationService creationService =
                new WorktreeCreationService(ghResources, worktreeService, projectRepository, repository);
        // No CLI title storage in these temp homes and no opencode process: every
        // lookup resolves to "no title", the fallback #373 defines as ordinary.
        ConsoleSessionTitles titles = new ConsoleSessionTitles(dbDir.resolve("claude"), dbDir.resolve("codex"),
                directory -> null);
        return new WorktreeController(worktreeService, creationService, sessionRegistry, titles);
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
