package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhClient;
import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhPullRequest;
import dev.locklane.engine.github.GhPullRequestDetail;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ShellSessionServiceTest {

    @Test
    void openingOnAnUnknownProjectIsEmpty(@TempDir Path dbDir) {
        ShellSessionService service = service(dbDir, TestSqliteDatabases.newProjectRepository(dbDir),
                TestSqliteDatabases.newRepository(dbDir));

        assertThat(service.open(999, 7, dbDir, "alice")).isEmpty();
    }

    @Test
    void openingOnAProjectStillCloningIsEmpty(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.create("proj", "url", dbDir.resolve("work"), 1L, Instant.now()).id();
        ShellSessionService service = service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        assertThat(service.open(projectId, 7, dbDir, "alice")).isEmpty();
    }

    @Test
    void openingAtAnIssueWorktreeMintsAWellFormedIdAndPersistsIt(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository);
        Path worktree = dbDir.resolve("proj-7");

        ShellSessionService.ShellSession session = service.open(projectId, 7, worktree, "alice").orElseThrow();

        assertThat(session.sessionId()).matches("^" + projectId + "-shell-7-[0-9a-f]{8}$");
        assertThat(session.workingDirectory()).isEqualTo(worktree.toString());
        // Persisted at mint time, so a WebSocket attach can resolve the directory
        // from the row with no ?dir= parameter.
        assertThat(sessionRepository.find(session.sessionId()))
                .map(WorktreeSessionRecord::workingDirectory).contains(worktree);
    }

    @Test
    void openingWithNoIssueTargetsTheMainCheckoutShape(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ShellSessionService service = service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        ShellSessionService.ShellSession session =
                service.open(projectId, null, dbDir.resolve("work"), "alice").orElseThrow();

        assertThat(session.sessionId()).matches("^" + projectId + "-shell-main-[0-9a-f]{8}$");
    }

    @Test
    void openingTwiceAtTheSameDirectoryMintsTwoSessions(@TempDir Path dbDir) {
        // Several shells at one location is the point (#444): one tailing logs,
        // another running a program.
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ShellSessionService service = service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        ShellSessionService.ShellSession first = service.open(projectId, 7, dbDir, "alice").orElseThrow();
        ShellSessionService.ShellSession second = service.open(projectId, 7, dbDir, "alice").orElseThrow();

        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
    }

    @Test
    void openingAsANonOwnerIsEmptyAndPersistsNothing(@TempDir Path dbDir) {
        // #460: minting persists a row the owner's listing shows and hasAnySessions
        // counts before any attach, so the owner gate has to hold at mint, not only
        // at the WebSocket attach the other session families rely on.
        long aliceId = createUser(dbDir, "alice");
        createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.open(projectId, 7, dbDir, "bob")).isEmpty();

        assertThat(sessionRepository.findAll()).isEmpty();
    }

    @Test
    void closingDeletesTheRowAndTheListingNoLongerShowsIt(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository);
        String shellId = service.open(projectId, 7, dbDir.resolve("proj-7"), "alice").orElseThrow().sessionId();

        assertThat(service.close(projectId, shellId, "alice")).isTrue();

        assertThat(sessionRepository.find(shellId)).isEmpty();
        assertThat(service.listOpen("alice")).isEmpty();
    }

    @Test
    void closingRefusesANonOwnerANonShellIdAndANeverPersistedId(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository);
        String shellId = service.open(projectId, 7, dbDir.resolve("proj-7"), "alice").orElseThrow().sessionId();
        // An ordinary agent session: in the project, but not in the shell family.
        sessionRepository.recordAttach(projectId + "-7-do-the-thing", dbDir.resolve("proj-7"),
                Instant.parse("2026-08-25T12:00:00Z"), "alice");

        assertThat(service.close(projectId, shellId, "bob")).isFalse();
        assertThat(service.close(projectId, projectId + "-7-do-the-thing", "alice")).isFalse();
        assertThat(service.close(projectId, projectId + "-shell-7-ffffffff", "alice")).isFalse();

        // Nothing was closed: both rows are still there.
        assertThat(sessionRepository.find(shellId)).isPresent();
        assertThat(sessionRepository.find(projectId + "-7-do-the-thing")).isPresent();
    }

    @Test
    void closingAnOpenShellBroadcastsConsolesChanged(@TempDir Path dbDir) {
        // The Shells window's live sidenav (#446) rides on this broadcast, so it is
        // pinned here even though SessionRegistry.close is what emits it.
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        EventBroadcaster broadcaster = mock(EventBroadcaster.class);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository,
                new SessionRegistry(sessionRepository, null, broadcaster));
        String shellId = service.open(projectId, null, dbDir.resolve("work"), "alice").orElseThrow().sessionId();

        assertThat(service.close(projectId, shellId, "alice")).isTrue();

        verify(broadcaster).broadcast("consolesChanged", Map.of("projectId", projectId));
    }

    @Test
    void listingGroupsByProjectAndIssueOrMainAndIsScopedToTheOwner(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository);
        ShellSessionService.ShellSession issueShell =
                service.open(projectId, 7, dbDir.resolve("proj-7"), "alice").orElseThrow();
        ShellSessionService.ShellSession mainShell =
                service.open(projectId, null, dbDir.resolve("work"), "alice").orElseThrow();

        List<ShellSessionService.OpenShell> alicesShells = service.listOpen("alice");

        assertThat(alicesShells).hasSize(2);
        ShellSessionService.OpenShell issueRow = alicesShells.stream()
                .filter(row -> row.sessionId().equals(issueShell.sessionId())).findFirst().orElseThrow();
        assertThat(issueRow.projectId()).isEqualTo(projectId);
        assertThat(issueRow.issueNumber()).isEqualTo(7);
        assertThat(issueRow.mainCheckout()).isFalse();
        assertThat(issueRow.workingDirectory()).isEqualTo(dbDir.resolve("proj-7").toString());
        ShellSessionService.OpenShell mainRow = alicesShells.stream()
                .filter(row -> row.sessionId().equals(mainShell.sessionId())).findFirst().orElseThrow();
        assertThat(mainRow.projectId()).isEqualTo(projectId);
        assertThat(mainRow.issueNumber()).isNull();
        assertThat(mainRow.mainCheckout()).isTrue();
        // The project-owner visibility rule (#242, #394): bob sees none of alice's shells.
        assertThat(service.listOpen("bob")).isEmpty();
    }

    @Test
    void shellSessionsStayOutOfTheExistingConsoleListings(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository);
        service.open(projectId, 7, dbDir.resolve("proj-7"), "alice").orElseThrow();
        // An ordinary agent session on the same issue, for contrast.
        sessionRepository.recordAttach(projectId + "-7-do-the-thing", dbDir.resolve("proj-7"),
                Instant.parse("2026-08-25T12:00:00Z"), "alice");
        IssueWorktreeService issueWorktreeService =
                new IssueWorktreeService(sessionRepository, authorization(dbDir, projectRepository));

        // The issue's console tab strip and the header indicator/picker both list
        // the agent session and never the shell.
        assertThat(issueWorktreeService.worktreeIdsForIssue(projectId, 7, "alice"))
                .containsExactly(projectId + "-7-do-the-thing");
        assertThat(issueWorktreeService.allWorktreeIds(projectId, "alice"))
                .containsExactly(projectId + "-7-do-the-thing");
        // The project consoles tab strip doesn't list it either.
        assertThat(projectConsoleService(dbDir, projectRepository, sessionRepository).listOpen(projectId, "alice"))
                .isEmpty();
    }

    @Test
    void shellSessionsCountForProjectDeleteRefusalAndCascadeDelete(@TempDir Path dbDir) {
        // Shells are tracked sessions like any other console (#444): an open shell
        // blocks a project delete, and deleting the owning user removes its rows.
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ShellSessionService service = service(dbDir, projectRepository, sessionRepository);
        String shellId = service.open(projectId, null, dbDir.resolve("work"), "alice").orElseThrow().sessionId();
        IssueWorktreeService issueWorktreeService =
                new IssueWorktreeService(sessionRepository, authorization(dbDir, projectRepository));

        assertThat(issueWorktreeService.hasAnySessions(projectId)).isTrue();

        issueWorktreeService.deleteSessionsForProject(projectId);

        assertThat(sessionRepository.find(shellId)).isEmpty();
        assertThat(issueWorktreeService.hasAnySessions(projectId)).isFalse();
    }

    private static ShellSessionService service(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository) {
        return service(dbDir, projectRepository, sessionRepository, new SessionRegistry(sessionRepository));
    }

    private static ShellSessionService service(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository, SessionRegistry sessionRegistry) {
        return new ShellSessionService(projectRepository, sessionRepository,
                authorization(dbDir, projectRepository), sessionRegistry);
    }

    private static ProjectConsoleService projectConsoleService(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository) {
        GhAccountRepository ghAccountRepository = TestSqliteDatabases.newGhAccountRepository(dbDir);
        ProjectGhResources ghResources = new ProjectGhResources(projectRepository, ghAccountRepository,
                tokenCipher(dbDir), (path, token) -> new FixedGhClient());
        WorktreeCleanupSweeper sweeper = new WorktreeCleanupSweeper(
                projectRepository, ghResources, new SessionRegistry(sessionRepository), ghAccountRepository,
                tokenCipher(dbDir));
        return new ProjectConsoleService(projectRepository, ghAccountRepository, tokenCipher(dbDir),
                new SessionRegistry(sessionRepository),
                sessionRepository, new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir)),
                authorization(dbDir, projectRepository), sweeper);
    }

    private static WorktreeSessionAuthorization authorization(Path dbDir, ProjectRepository projectRepository) {
        return new WorktreeSessionAuthorization(projectRepository, TestSqliteDatabases.newUserRepository(dbDir));
    }

    private static long createUser(Path dbDir, String username) {
        return TestSqliteDatabases.newUserRepository(dbDir).create(username, "bcrypt-hash", Instant.now()).id();
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class FixedGhClient implements GhClient {
        @Override
        public List<GhIssue> issues() {
            return List.of();
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
