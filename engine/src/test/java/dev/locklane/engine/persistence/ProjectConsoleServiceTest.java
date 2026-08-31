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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ProjectConsoleServiceTest {

    private static final Instant EARLIER = Instant.parse("2026-08-25T12:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-08-25T12:30:00Z");
    private static final Instant LATER = Instant.parse("2026-08-25T13:00:00Z");

    @Test
    void startingOnAnUnknownProjectIsEmpty(@TempDir Path dbDir) {
        ProjectConsoleService service = service(dbDir);

        assertThat(service.start(999)).isEmpty();
    }

    @Test
    void startingOnAProjectStillCloningIsEmpty(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.create("proj", "url", dbDir.resolve("work"), 1L, Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.start(projectId)).isEmpty();
    }

    @Test
    void startingOnAReadyProjectMintsAFreshFamilyIdEveryCall(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", 1L, Instant.now()).id();
        ProjectConsoleService service = service(tmp, projectRepository);

        Optional<ProjectConsoleService.ConsoleSession> first = service.start(projectId);
        Optional<ProjectConsoleService.ConsoleSession> second = service.start(projectId);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().sessionId()).matches("^" + projectId + "-console-[0-9a-f]{8}$");
        // Several consoles side by side (#177): a second open is a new session, never a reuse.
        assertThat(second.get().sessionId()).isNotEqualTo(first.get().sessionId())
                .matches("^" + projectId + "-console-[0-9a-f]{8}$");
    }

    @Test
    void startingCreatesTheWorktreeDetachedAtOriginMainWithNoConsoleBranch(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", 1L, Instant.now()).id();
        ProjectConsoleService service = service(tmp, projectRepository);

        ProjectConsoleService.ConsoleSession session = service.start(projectId).get();

        // Detached HEAD, not a freshly minted console/<suffix> branch (#338).
        assertThat(GitTestRepos.currentBranch(Path.of(session.workingDirectory()))).isEmpty();
        assertThat(GitTestRepos.branchList(workarea, "console/*")).isEmpty();
    }

    @Test
    void startingCreatesAFreshSiblingWorktreePerSessionNeverTheSharedCheckout(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", 1L, Instant.now()).id();
        ProjectConsoleService service = service(tmp, projectRepository);

        Optional<ProjectConsoleService.ConsoleSession> first = service.start(projectId);
        Optional<ProjectConsoleService.ConsoleSession> second = service.start(projectId);

        // Neither session reuses the project's own shared checkout (#314) ...
        assertThat(first.get().workingDirectory()).isNotEqualTo(workarea.toString());
        assertThat(second.get().workingDirectory()).isNotEqualTo(workarea.toString());
        // ... and pressing "+" twice produces two separate worktrees, not a reuse.
        assertThat(second.get().workingDirectory()).isNotEqualTo(first.get().workingDirectory());
        assertThat(Path.of(first.get().workingDirectory())).isDirectory();
        assertThat(Path.of(second.get().workingDirectory())).isDirectory();
    }

    @Test
    void closingACleanDetachedNoCommitsSessionRemovesItsWorktree(@TempDir Path tmp) throws Exception {
        // #339/ADR-104: a fresh console worktree is detached at origin/main, clean,
        // and has no commits of its own -- every guard condition clears, so closing
        // the tab removes it, unlike before this task (#314's deferred cleanup).
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice"); // id 1
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession session = service.start(projectId).get();
        sessionRepository.recordAttach(session.sessionId(), Path.of(session.workingDirectory()), EARLIER, "alice");

        assertThat(service.close(projectId, "alice")).isTrue();

        assertThat(Path.of(session.workingDirectory())).doesNotExist();
        assertThat(sessionRepository.find(session.sessionId())).isEmpty();
    }

    @Test
    void closingADirtySessionKeepsItsWorktree(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession session = service.start(projectId).get();
        sessionRepository.recordAttach(session.sessionId(), Path.of(session.workingDirectory()), EARLIER, "alice");
        GitTestRepos.makeDirty(Path.of(session.workingDirectory()));

        assertThat(service.close(projectId, "alice")).isTrue();

        // The session itself still ends -- only the worktree survives.
        assertThat(sessionRepository.find(session.sessionId())).isEmpty();
        assertThat(Path.of(session.workingDirectory())).isDirectory();
    }

    @Test
    void closingASessionWithABranchCheckedOutKeepsItsWorktree(@TempDir Path tmp) throws Exception {
        // The branch-checked-out guard: a console that outgrew scratch use (e.g. via
        // /t-work) is left alone permanently (ADR-005), never swept as scratch.
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession session = service.start(projectId).get();
        sessionRepository.recordAttach(session.sessionId(), Path.of(session.workingDirectory()), EARLIER, "alice");
        GitTestRepos.run(Path.of(session.workingDirectory()), "git", "checkout", "-b", "wip/1-do-the-thing");

        assertThat(service.close(projectId, "alice")).isTrue();

        assertThat(sessionRepository.find(session.sessionId())).isEmpty();
        assertThat(Path.of(session.workingDirectory())).isDirectory();
    }

    @Test
    void closingASessionWithCommitsNotOnOriginMainKeepsItsWorktree(@TempDir Path tmp) throws Exception {
        // A commit made on detached HEAD, never pushed anywhere -- removing the
        // worktree here would destroy it forever along with its reflog.
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession session = service.start(projectId).get();
        sessionRepository.recordAttach(session.sessionId(), Path.of(session.workingDirectory()), EARLIER, "alice");
        GitTestRepos.commitEmpty(Path.of(session.workingDirectory()), "unpushed work on detached HEAD");

        assertThat(service.close(projectId, "alice")).isTrue();

        assertThat(sessionRepository.find(session.sessionId())).isEmpty();
        assertThat(Path.of(session.workingDirectory())).isDirectory();
    }

    @Test
    void findsNothingBeforeAnySessionHasEverBeenAttachedTo(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.find(projectId, "alice")).isEmpty();
    }

    @Test
    void findsAFamilySessionOnceAttachedAndVisibleToItsOwner(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(projectId, "alice")).map(ProjectConsoleService.ConsoleSession::sessionId)
                .contains(projectId + "-console-0a1b2c3d");
    }

    @Test
    void findsALegacyPreFamilySessionUnchanged(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        // The bare "<projectId>-console" id the pre-#177 code minted, persisted before the upgrade.
        sessionRepository.recordAttach(projectId + "-console", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(projectId, "alice")).map(ProjectConsoleService.ConsoleSession::sessionId)
                .contains(projectId + "-console");
    }

    @Test
    void findsTheMostRecentlyAttachedOfSeveralOpenConsoles(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(projectId, "alice")).map(ProjectConsoleService.ConsoleSession::sessionId)
                .contains(projectId + "-console-bbbbbbbb");
    }

    @Test
    void doesNotFindAnotherProjectsSession(@TempDir Path dbDir) {
        createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        // #242: visibility now follows the project's owner, not whoever attached --
        // a session in bob's own project is invisible to alice regardless of who
        // (even alice herself) happened to attach to it.
        long bobsProjectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", bobId, Instant.now()).id();
        sessionRepository.recordAttach(bobsProjectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(bobsProjectId, "alice")).isEmpty();
    }

    /**
     * #394 (ADR-105): an administrator reaches another account's console no more than
     * any other non-owner does — it is neither findable, nor listed, nor closable.
     */
    @Test
    void doesNotFindOrListOrCloseAnotherUsersConsoleForAnAdmin(@TempDir Path dbDir) {
        long bobId = createUser(dbDir, "bob");
        createAdminUser(dbDir, "root");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long bobsProjectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", bobId, Instant.now()).id();
        sessionRepository.recordAttach(bobsProjectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(bobsProjectId, "root")).isEmpty();
        assertThat(service.listOpen(bobsProjectId, "root")).isEmpty();
        assertThat(service.close(bobsProjectId, bobsProjectId + "-console-0a1b2c3d", "root")).isFalse();
        assertThat(service.listOpen(bobsProjectId, "bob"))
                .extracting(ProjectConsoleService.OpenConsole::sessionId)
                .containsExactly(bobsProjectId + "-console-0a1b2c3d");
    }

    @Test
    void listsOpenConsolesOldestFirstWithTheirTimes(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console", dbDir, MIDDLE, null); // legacy id, no recorded attacher
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.listOpen(projectId, "alice"))
                .extracting(ProjectConsoleService.OpenConsole::sessionId, ProjectConsoleService.OpenConsole::createdAt)
                .containsExactly(
                        tuple(projectId + "-console-aaaaaaaa", EARLIER),
                        tuple(projectId + "-console", MIDDLE),
                        tuple(projectId + "-console-bbbbbbbb", LATER));
    }

    @Test
    void listExcludesOtherProjectsAndIssueSessions(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        long otherProjectId = projectRepository
                .createReady("other", "url", dbDir.resolve("other-work"), "main", bobId, Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        // #242: bob attaching to a session inside alice's own project no longer
        // hides it from her -- visibility is per-project now, not per-attacher.
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, EARLIER, "bob");
        sessionRepository.recordAttach(otherProjectId + "-console-cccccccc", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-174-some-worktree", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach("main", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.listOpen(projectId, "alice"))
                .extracting(ProjectConsoleService.OpenConsole::sessionId)
                .containsExactlyInAnyOrder(projectId + "-console-aaaaaaaa", projectId + "-console-bbbbbbbb");
    }

    @Test
    void listIsEmptyForAProjectWithNoOpenConsole(@TempDir Path dbDir) {
        ProjectConsoleService service = service(dbDir);

        assertThat(service.listOpen(999, "alice")).isEmpty();
    }

    @Test
    void closingWithNoOpenConsoleDoesNothingAndReportsFalse(@TempDir Path dbDir) {
        ProjectConsoleService service = service(dbDir);

        assertThat(service.close(1, "alice")).isFalse();
    }

    @Test
    void closingAnotherProjectsSessionIsRefusedAndLeavesItRunning(@TempDir Path dbDir) {
        createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long bobsProjectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", bobId, Instant.now()).id();
        sessionRepository.recordAttach(bobsProjectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.close(bobsProjectId, "alice")).isFalse();
        assertThat(sessionRepository.find(bobsProjectId + "-console-0a1b2c3d")).isPresent();
    }

    @Test
    void closingTheOwnersCurrentSessionRemovesItsRecord(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.close(projectId, "alice")).isTrue();
        assertThat(sessionRepository.find(projectId + "-console-0a1b2c3d")).isEmpty();
    }

    @Test
    void closingOneSpecificConsoleLeavesItsSiblingsOpen(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.close(projectId, projectId + "-console-aaaaaaaa", "alice")).isTrue();
        assertThat(sessionRepository.find(projectId + "-console-aaaaaaaa")).isEmpty();
        assertThat(sessionRepository.find(projectId + "-console-bbbbbbbb")).isPresent();
    }

    @Test
    void closingBySessionIdRefusesIdsOutsideTheProjectsFamilyOrBelongingToAnotherProject(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        long otherProjectId = projectRepository
                .createReady("other", "url", dbDir.resolve("other-work"), "main", bobId, Instant.now()).id();
        sessionRepository.recordAttach(otherProjectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-174-some-worktree", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        // Another project's console, reached through this project's id.
        assertThat(service.close(projectId, otherProjectId + "-console-aaaaaaaa", "alice")).isFalse();
        // The same console, reached through its own (correct) project id -- still
        // refused because alice isn't that project's owner (#242).
        assertThat(service.close(otherProjectId, otherProjectId + "-console-aaaaaaaa", "alice")).isFalse();
        // An issue session is never a project console, whatever the caller claims.
        assertThat(service.close(projectId, projectId + "-174-some-worktree", "alice")).isFalse();
        // One that was never attached to.
        assertThat(service.close(projectId, projectId + "-console-cccccccc", "alice")).isFalse();
        assertThat(sessionRepository.findAll()).hasSize(2);
    }

    @Test
    void environmentForANonConsoleSessionIdIsEmpty(@TempDir Path dbDir) {
        ProjectConsoleService service = service(dbDir);

        assertThat(service.environmentFor("42-174-some-worktree")).isEmpty();
        assertThat(service.environmentFor("main")).isEmpty();
    }

    @Test
    void environmentForAConsoleSessionWithNoStoredTokenIsEmpty(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", 1L, Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.environmentFor(projectId + "-console-0a1b2c3d")).isEmpty();
    }

    @Test
    void environmentForAConsoleSessionDecryptsTheStoredToken(@TempDir Path dbDir) throws IOException {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", 1L, Instant.now()).id();
        TokenCipher tokenCipher = tokenCipher(dbDir);
        projectRepository.setGithubToken(projectId, tokenCipher.encrypt("ghp_realtoken"));
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectConsoleService service = new ProjectConsoleService(projectRepository, tokenCipher,
                new SessionRegistry(sessionRepository), sessionRepository,
                new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir)), authorization(dbDir, projectRepository),
                sweeper(dbDir, sessionRepository, projectRepository));

        // Both the #177 family and the legacy pre-#177 id resolve the token.
        assertThat(service.environmentFor(projectId + "-console-0a1b2c3d"))
                .isEqualTo(Map.of("GH_TOKEN", "ghp_realtoken"));
        assertThat(service.environmentFor(projectId + "-console")).isEqualTo(Map.of("GH_TOKEN", "ghp_realtoken"));
    }

    // ---- #372: past conversations in a project's own consoles ----

    @Test
    void listsThisProjectsPastConversationsNewestSightingFirst(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ConsoleResumeSessionRepository resumeRepository = new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record(projectId + "-console-aaaaaaaa", "claude", "11111111-1111-1111-1111-111111111111",
                EARLIER);
        resumeRepository.record(projectId + "-console-bbbbbbbb", "opencode", "ses_01ABCDEFGHIJKLMNOPQRSTUVWX", LATER);
        ProjectConsoleService service =
                service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir), resumeRepository);

        assertThat(service.resumeSessionsForProject(projectId, "alice"))
                .extracting(ConsoleResumeSessionRecord::tool, ConsoleResumeSessionRecord::resumeId)
                .containsExactly(
                        tuple("opencode", "ses_01ABCDEFGHIJKLMNOPQRSTUVWX"),
                        tuple("claude", "11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void listsAConversationSightedInSeveralConsolesOnceAtItsNewestSighting(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ConsoleResumeSessionRepository resumeRepository = new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        String conversation = "11111111-1111-1111-1111-111111111111";
        resumeRepository.record(projectId + "-console-aaaaaaaa", "claude", conversation, EARLIER);
        resumeRepository.record(projectId + "-console-aaaaaaaa-resume-bbbbbbbb", "claude", conversation, LATER);
        ProjectConsoleService service =
                service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir), resumeRepository);

        assertThat(service.resumeSessionsForProject(projectId, "alice"))
                .extracting(ConsoleResumeSessionRecord::worktreeId)
                .containsExactly(projectId + "-console-aaaaaaaa-resume-bbbbbbbb");
    }

    @Test
    void doesNotListAnotherProjectsConsolesOrAnIssuesOwnOrTheLegacySharedCheckoutId(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        long otherProjectId =
                projectRepository.createReady("other", "url", dbDir.resolve("other"), "main", aliceId, Instant.now())
                        .id();
        ConsoleResumeSessionRepository resumeRepository = new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record(otherProjectId + "-console-aaaaaaaa", "claude",
                "11111111-1111-1111-1111-111111111111", LATER);
        // An issue's own console, in this same project -- the issue page lists it.
        resumeRepository.record(projectId + "-174-some-slug", "claude", "22222222-2222-2222-2222-222222222222", LATER);
        // The pre-#177 bare id: it only ever ran in the project's shared checkout,
        // which #341 retired as a console location, so a reopen could never work.
        resumeRepository.record(projectId + "-console", "claude", "33333333-3333-3333-3333-333333333333", LATER);
        ProjectConsoleService service =
                service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir), resumeRepository);

        assertThat(service.resumeSessionsForProject(projectId, "alice")).isEmpty();
    }

    @Test
    void doesNotListAConversationFromAConsoleTheCallerCannotSee(@TempDir Path dbDir) {
        createUser(dbDir, "alice");
        long bobId = createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long bobsProjectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", bobId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        sessionRepository.recordAttach(bobsProjectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        ConsoleResumeSessionRepository resumeRepository = new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir));
        resumeRepository.record(bobsProjectId + "-console-aaaaaaaa", "claude",
                "11111111-1111-1111-1111-111111111111", LATER);
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository, resumeRepository);

        assertThat(service.resumeSessionsForProject(bobsProjectId, "alice")).isEmpty();
    }

    @Test
    void reopeningRunsInTheOriginalConsolesRecordedDirectory(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession original = service.start(projectId).get();
        sessionRepository.recordAttach(original.sessionId(), Path.of(original.workingDirectory()), EARLIER, "alice");

        ProjectConsoleService.ConsoleSession reopened = service.reopenSession(projectId, original.sessionId()).get();

        assertThat(reopened.workingDirectory()).isEqualTo(original.workingDirectory());
        // A brand-new session, never a reattach to the one still running.
        assertThat(reopened.sessionId()).isNotEqualTo(original.sessionId())
                .matches("^" + projectId + "-console-[0-9a-f]{8}-resume-[0-9a-f]{8}$");
    }

    @Test
    void reopeningAClosedConsoleRebuildsItsWorktreeAtTheSamePath(@TempDir Path tmp) throws Exception {
        // The whole point of #372: a project console's tab-close deletes both its
        // session record and (#339) its worktree, so the directory a conversation is
        // keyed to has to come back from the session id alone.
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession original = service.start(projectId).get();
        sessionRepository.recordAttach(original.sessionId(), Path.of(original.workingDirectory()), EARLIER, "alice");
        assertThat(service.close(projectId, original.sessionId(), "alice")).isTrue();
        assertThat(Path.of(original.workingDirectory())).doesNotExist();

        ProjectConsoleService.ConsoleSession reopened = service.reopenSession(projectId, original.sessionId()).get();

        assertThat(reopened.workingDirectory()).isEqualTo(original.workingDirectory());
        assertThat(Path.of(reopened.workingDirectory())).isDirectory();
        assertThat(GitTestRepos.currentBranch(Path.of(reopened.workingDirectory()))).isEmpty();
    }

    @Test
    void reopeningAnAlreadyReopenedConsoleStillLandsInTheOriginalDirectory(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        ProjectConsoleService service = service(tmp, projectRepository);
        ProjectConsoleService.ConsoleSession original = service.start(projectId).get();

        ProjectConsoleService.ConsoleSession once = service.reopenSession(projectId, original.sessionId()).get();
        ProjectConsoleService.ConsoleSession twice = service.reopenSession(projectId, once.sessionId()).get();

        assertThat(once.workingDirectory()).isEqualTo(original.workingDirectory());
        assertThat(twice.workingDirectory()).isEqualTo(original.workingDirectory());
        // The id never grows a chain of resume tails, so the directory stays derivable.
        assertThat(twice.sessionId()).matches("^" + projectId + "-console-[0-9a-f]{8}-resume-[0-9a-f]{8}$");
    }

    @Test
    void refusesToReopenTheLegacySharedCheckoutConsoleOrAnythingOutsideTheProject(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        ProjectConsoleService service = service(tmp, projectRepository);

        assertThat(service.reopenSession(projectId, projectId + "-console")).isEmpty();
        assertThat(service.reopenSession(projectId, projectId + "-174-some-slug")).isEmpty();
        assertThat(service.reopenSession(projectId, (projectId + 1) + "-console-aaaaaaaa")).isEmpty();
        assertThat(service.reopenSession(999, "999-console-aaaaaaaa")).isEmpty();
    }

    @Test
    void resolvesAConversationsDirectoryFromItsRecordAndThenFromItsIdAlone(@TempDir Path tmp) throws Exception {
        // #373's title lookup needs the same answer #372's reopen does: Claude and
        // OpenCode file a stored conversation under the directory it ran in.
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession session = service.start(projectId).get();

        // Before any attach there is no record: the id alone names the directory.
        assertThat(service.conversationDirectory(projectId, session.sessionId()))
                .contains(Path.of(session.workingDirectory()));

        sessionRepository.recordAttach(session.sessionId(), Path.of(session.workingDirectory()), EARLIER, "alice");
        assertThat(service.conversationDirectory(projectId, session.sessionId()))
                .contains(Path.of(session.workingDirectory()));
        // A reopened console runs in the original's directory, so it resolves there too.
        assertThat(service.conversationDirectory(projectId, session.sessionId() + "-resume-99887766"))
                .contains(Path.of(session.workingDirectory()));
    }

    @Test
    void hasNoConversationDirectoryOutsideTheProjectOrForTheLegacySharedCheckoutId(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId =
                projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.conversationDirectory(projectId, projectId + "-console")).isEmpty();
        assertThat(service.conversationDirectory(projectId, projectId + "-174-some-slug")).isEmpty();
        assertThat(service.conversationDirectory(projectId, (projectId + 1) + "-console-aaaaaaaa")).isEmpty();
    }

    /** An administrator account — the role #394 deliberately gives no extra reach. */
    private static long createAdminUser(Path dbDir, String username) {
        return TestSqliteDatabases.newUserRepository(dbDir)
                .create(username, "bcrypt-hash", Instant.now(), UserRecord.Role.ADMIN).id();
    }

    private static long createUser(Path dbDir, String username) {
        return TestSqliteDatabases.newUserRepository(dbDir).create(username, "bcrypt-hash", Instant.now()).id();
    }

    private static WorktreeSessionAuthorization authorization(Path dbDir, ProjectRepository projectRepository) {
        return new WorktreeSessionAuthorization(projectRepository, TestSqliteDatabases.newUserRepository(dbDir));
    }

    private static ProjectConsoleService service(Path dbDir) {
        return service(dbDir, TestSqliteDatabases.newProjectRepository(dbDir));
    }

    private static ProjectConsoleService service(Path dbDir, ProjectRepository projectRepository) {
        return service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));
    }

    private static ProjectConsoleService service(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository) {
        return service(dbDir, projectRepository, sessionRepository, new ConsoleResumeSessionRepository(TestSqliteDatabases.newDataSource(dbDir)));
    }

    private static ProjectConsoleService service(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository, ConsoleResumeSessionRepository resumeRepository) {
        return new ProjectConsoleService(projectRepository, tokenCipher(dbDir), new SessionRegistry(sessionRepository),
                sessionRepository, resumeRepository, authorization(dbDir, projectRepository),
                sweeper(dbDir, sessionRepository, projectRepository));
    }

    /**
     * A {@link WorktreeCleanupSweeper} wired for these tests — no real GitHub issues
     * are ever involved here, so an empty {@link FixedGhClient} is enough; #339's
     * project-console guard/removal is what these tests actually exercise via
     * {@link ProjectConsoleService#close}.
     */
    private static WorktreeCleanupSweeper sweeper(Path dbDir, WorktreeSessionRepository sessionRepository,
            ProjectRepository projectRepository) {
        IssueWorktreeService worktreeService =
                new IssueWorktreeService(sessionRepository, TestSqliteDatabases.newNoopAuthorization());
        ProjectGhResources ghResources =
                new ProjectGhResources(projectRepository, tokenCipher(dbDir), (path, token) -> new FixedGhClient());
        return new WorktreeCleanupSweeper(worktreeService, projectRepository, ghResources,
                new SessionRegistry(sessionRepository));
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
