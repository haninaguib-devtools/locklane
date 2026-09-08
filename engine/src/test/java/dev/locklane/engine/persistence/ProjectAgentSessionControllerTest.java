package dev.locklane.engine.persistence;

import dev.locklane.engine.agent.InstalledAgentsStore;
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
import static org.assertj.core.api.Assertions.tuple;

class ProjectAgentSessionControllerTest {
    // Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories and the
    // /console and /consoles REST paths below keep their persisted and on-the-wire shape: compatibility
    // surfaces kept under ADR-112 (#766 renamed only the identifiers).

    private static final Principal ALICE = () -> "alice";
    private static final Principal BOB = () -> "bob";
    private static final Instant EARLIER = Instant.parse("2026-08-25T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-25T13:00:00Z");

    @Test
    void startingOnAnUnknownProjectIsNotFound(@TempDir Path dbDir) {
        ProjectAgentSessionController controller = controller(dbDir, TestSqliteDatabases.newProjectRepository(dbDir),
                TestSqliteDatabases.newRepository(dbDir));

        assertThat(controller.start(999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void startingOnAReadyProjectMintsAFreshSessionIdEveryCall(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", 1L, Instant.now()).id();
        ProjectAgentSessionController controller =
                controller(tmp, projectRepository, TestSqliteDatabases.newRepository(tmp));

        var first = controller.start(projectId);
        var second = controller.start(projectId);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("sessionId")).matches("^" + projectId + "-console-[0-9a-f]{8}$");
        // A fresh sibling worktree per session (#314), never the shared checkout, never reused.
        assertThat(first.getBody().get("workingDirectory")).isNotEqualTo(workarea.toString());
        assertThat(second.getBody().get("workingDirectory")).isNotEqualTo(first.getBody().get("workingDirectory"));
        assertThat(second.getBody().get("sessionId")).isNotEqualTo(first.getBody().get("sessionId"));
    }

    @Test
    void discoveringBeforeAnyAttachIsNotFound(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ProjectAgentSessionController controller =
                controller(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void discoveringAfterAnAttachReturnsItToItsOwner(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        var response = controller.get(projectId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("sessionId", projectId + "-console-0a1b2c3d");
    }

    @Test
    void discoveringAnotherProjectsSessionIsNotFound(@TempDir Path dbDir) {
        createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        // #242: visibility follows the project's owner, not whoever attached.
        long bobsProjectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", bobId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(bobsProjectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.get(bobsProjectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listingOpenAgentSessionsReturnsTheCallersOldestFirst(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        // A session in bob's own (different) project must never appear in alice's list.
        long bobsProjectId = projectRepository
                .createReady("bobs-proj", "url", dbDir.resolve("bobs-work"), "main", bobId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(bobsProjectId + "-console-cccccccc", dbDir, EARLIER, "bob");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.sessions(projectId, ALICE))
                .extracting(ProjectAgentSessionController.OpenAgentSessionView::sessionId,
                        ProjectAgentSessionController.OpenAgentSessionView::createdAt)
                .containsExactly(
                        tuple(projectId + "-console-aaaaaaaa", EARLIER.toString()),
                        tuple(projectId + "-console-bbbbbbbb", LATER.toString()));
    }

    @Test
    void listingAProjectWithNoOpenAgentSessionIsEmpty(@TempDir Path dbDir) {
        ProjectAgentSessionController controller = controller(dbDir, TestSqliteDatabases.newProjectRepository(dbDir),
                TestSqliteDatabases.newRepository(dbDir));

        assertThat(controller.sessions(999, ALICE)).isEmpty();
    }

    @Test
    void closingTheOwnersSessionSucceeds(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        var response = controller.close(projectId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.get(projectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void closingAnotherProjectsSessionIsNotFoundAndLeavesItRunning(@TempDir Path dbDir) {
        createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long bobsProjectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", bobId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(bobsProjectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.close(bobsProjectId, ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.get(bobsProjectId, BOB).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void closingOneAgentSessionByIdLeavesItsSiblingsOpen(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        var response = controller.close(projectId, projectId + "-console-aaaaaaaa", ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.sessions(projectId, ALICE))
                .extracting(ProjectAgentSessionController.OpenAgentSessionView::sessionId)
                .containsExactly(projectId + "-console-bbbbbbbb");
    }

    @Test
    void closingByIdRefusesAnIdOutsideTheProjectsFamilyOrBelongingToAnotherProject(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        long bobsProjectId = projectRepository
                .createReady("bobs-proj", "url", dbDir.resolve("bobs-work"), "main", bobId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-174-some-worktree", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(bobsProjectId + "-console-bbbbbbbb", dbDir, EARLIER, "bob");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        // An issue session is never a project agent session, whatever the caller claims.
        assertThat(controller.close(projectId, projectId + "-174-some-worktree", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // Another project's agent session, reached through this project's id.
        assertThat(controller.close(projectId, bobsProjectId + "-console-bbbbbbbb", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // The same agent session, reached through its own (correct) project id -- still
        // refused because alice isn't that project's owner (#242).
        assertThat(controller.close(bobsProjectId, bobsProjectId + "-console-bbbbbbbb", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sessionRepository.findAll()).hasSize(2);
    }

    // ---- #372: past conversations in a project's own agent sessions ----

    @Test
    void listsThisProjectsPastConversationsForItsOwner(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir)).record(projectId + "-console-aaaaaaaa", "claude",
                "11111111-1111-1111-1111-111111111111", LATER);
        ProjectAgentSessionController controller =
                controller(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        assertThat(controller.resumeSessions(projectId, ALICE))
                .extracting(WorktreeController.ResumeSessionView::worktreeId,
                        WorktreeController.ResumeSessionView::tool,
                        WorktreeController.ResumeSessionView::resumeId,
                        WorktreeController.ResumeSessionView::capturedAt,
                        WorktreeController.ResumeSessionView::title)
                .containsExactly(tuple(projectId + "-console-aaaaaaaa", "claude",
                        "11111111-1111-1111-1111-111111111111", LATER.toString(), null));
        // Someone who is not the project's owner sees none of them (#242).
        assertThat(controller.resumeSessions(projectId, BOB)).isEmpty();
    }

    @Test
    void reopeningAConversationMintsASessionInItsOriginalDirectory(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectAgentSessionController controller = controller(tmp, projectRepository, sessionRepository);
        var original = controller.start(projectId).getBody();
        String originalId = original.get("sessionId");
        String originalDirectory = original.get("workingDirectory");
        sessionRepository.recordAttach(originalId, Path.of(originalDirectory), EARLIER, "alice");
        new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(tmp)).record(originalId, "claude",
                "11111111-1111-1111-1111-111111111111", LATER);

        var response = controller.reopenSession(projectId, originalId, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("workingDirectory")).isEqualTo(originalDirectory);
        assertThat(response.getBody().get("sessionId"))
                .matches("^" + projectId + "-console-[0-9a-f]{8}-resume-[0-9a-f]{8}$");
    }

    @Test
    void reopeningAConversationTheCallerCannotSeeIsNotFound(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        createUser(tmp, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        ProjectAgentSessionController controller =
                controller(tmp, projectRepository, TestSqliteDatabases.newRepository(tmp));
        new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(tmp)).record(projectId + "-console-aaaaaaaa", "claude",
                "11111111-1111-1111-1111-111111111111", LATER);

        // Not this project's owner.
        assertThat(controller.reopenSession(projectId, projectId + "-console-aaaaaaaa", BOB).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // An agent session with no captured conversation at all.
        assertThat(controller.reopenSession(projectId, projectId + "-console-bbbbbbbb", ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void namingAAgentSessionTabShowsThatNameInTheOwnersListing(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        var response = controller.rename(projectId, projectId + "-console-aaaaaaaa",
                new ProjectAgentSessionController.RenameRequest("  release notes  "), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Stored trimmed, and read back from the database rather than from memory --
        // this is the "survives a reload, same in any browser" promise (#393).
        assertThat(controller.sessions(projectId, ALICE))
                .extracting(ProjectAgentSessionController.OpenAgentSessionView::displayName)
                .containsExactly("release notes");
    }

    @Test
    void clearingAAgentSessionTabsNameRestoresTheAutoLabel(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);
        controller.rename(projectId, projectId + "-console-aaaaaaaa",
                new ProjectAgentSessionController.RenameRequest("release notes"), ALICE);

        // Whitespace-only is a clear, not a name made of spaces.
        var response = controller.rename(projectId, projectId + "-console-aaaaaaaa",
                new ProjectAgentSessionController.RenameRequest("   "), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.sessions(projectId, ALICE))
                .extracting(ProjectAgentSessionController.OpenAgentSessionView::displayName)
                .containsOnlyNulls();
    }

    @Test
    void anOverLongNameIsRejectedRatherThanTruncated(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        String tooLong = "x".repeat(ProjectAgentSessionService.MAX_DISPLAY_NAME_LENGTH + 1);
        var response = controller.rename(projectId, projectId + "-console-aaaaaaaa",
                new ProjectAgentSessionController.RenameRequest(tooLong), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.sessions(projectId, ALICE))
                .extracting(ProjectAgentSessionController.OpenAgentSessionView::displayName)
                .containsOnlyNulls();

        // The bound itself is inclusive -- exactly the limit is accepted.
        assertThat(controller.rename(projectId, projectId + "-console-aaaaaaaa",
                new ProjectAgentSessionController.RenameRequest("x".repeat(ProjectAgentSessionService.MAX_DISPLAY_NAME_LENGTH)),
                ALICE).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void renamingAnotherOwnersAgentSessionIsNotFoundAndChangesNothing(@TempDir Path dbDir) {
        createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long bobsProjectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", bobId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(bobsProjectId + "-console-bbbbbbbb", dbDir, EARLIER, "bob");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        assertThat(controller.rename(bobsProjectId, bobsProjectId + "-console-bbbbbbbb",
                new ProjectAgentSessionController.RenameRequest("alice was here"), ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.sessions(bobsProjectId, BOB))
                .extracting(ProjectAgentSessionController.OpenAgentSessionView::displayName)
                .containsOnlyNulls();
    }

    @Test
    void renamingAnIdOutsideTheProjectsAgentSessionFamilyIsNotFound(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(projectId + "-174-some-worktree", dbDir, EARLIER, "alice");
        ProjectAgentSessionController controller = controller(dbDir, projectRepository, sessionRepository);

        // An issue's worktree session is never a project agent session tab, and an agent session
        // that was never attached to has no record to name.
        assertThat(controller.rename(projectId, projectId + "-174-some-worktree",
                new ProjectAgentSessionController.RenameRequest("nope"), ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.rename(projectId, projectId + "-console-99999999",
                new ProjectAgentSessionController.RenameRequest("nope"), ALICE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static long createUser(Path dbDir, String username) {
        return TestSqliteDatabases.newUserRepository(dbDir).create(username, "bcrypt-hash", Instant.now()).id();
    }

    private static ProjectAgentSessionController controller(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository) {
        WorktreeSessionAuthorization authorization =
                new WorktreeSessionAuthorization(projectRepository, TestSqliteDatabases.newUserRepository(dbDir));
        GhAccountRepository ghAccountRepository = TestSqliteDatabases.newGhAccountRepository(dbDir);
        ProjectGhResources ghResources = new ProjectGhResources(projectRepository, ghAccountRepository,
                tokenCipher(dbDir), (path, token) -> new FixedGhClient());
        WorktreeCleanupSweeper sweeper = new WorktreeCleanupSweeper(projectRepository, ghResources,
                new SessionRegistry(sessionRepository), ghAccountRepository, tokenCipher(dbDir));
        AgentSessionTitles titles = new AgentSessionTitles(dbDir.resolve("claude"), dbDir.resolve("codex"),
                directory -> null);
        return new ProjectAgentSessionController(new ProjectAgentSessionService(projectRepository, ghAccountRepository,
                tokenCipher(dbDir), new SessionRegistry(sessionRepository), sessionRepository,
                new AgentSessionResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir)), authorization, sweeper),
                titles, new InstalledAgentsStore());
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
