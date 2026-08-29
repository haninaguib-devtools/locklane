package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
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
    void closingASessionLeavesItsWorktreeOnDisk(@TempDir Path tmp) throws Exception {
        Path workarea = GitTestRepos.initTestRepo(tmp);
        long aliceId = createUser(tmp, "alice"); // id 1
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(tmp);
        ProjectConsoleService service = service(tmp, projectRepository, sessionRepository);
        ProjectConsoleService.ConsoleSession session = service.start(projectId).get();
        sessionRepository.recordAttach(session.sessionId(), Path.of(session.workingDirectory()), EARLIER, "alice");

        assertThat(service.close(projectId, "alice")).isTrue();

        // Cleanup is deliberately out of scope for #314 -- the worktree stays put.
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
                new SessionRegistry(sessionRepository), sessionRepository, authorization(dbDir, projectRepository));

        // Both the #177 family and the legacy pre-#177 id resolve the token.
        assertThat(service.environmentFor(projectId + "-console-0a1b2c3d"))
                .isEqualTo(Map.of("GH_TOKEN", "ghp_realtoken"));
        assertThat(service.environmentFor(projectId + "-console")).isEqualTo(Map.of("GH_TOKEN", "ghp_realtoken"));
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
        return new ProjectConsoleService(projectRepository, tokenCipher(dbDir), new SessionRegistry(sessionRepository),
                sessionRepository, authorization(dbDir, projectRepository));
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
