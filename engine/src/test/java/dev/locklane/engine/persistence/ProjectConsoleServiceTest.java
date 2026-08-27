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
        long projectId = projectRepository.create("proj", "url", dbDir.resolve("work"), Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.start(projectId)).isEmpty();
    }

    @Test
    void startingOnAReadyProjectMintsAFreshFamilyIdEveryCall(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        Path workarea = dbDir.resolve("work");
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        Optional<ProjectConsoleService.ConsoleSession> first = service.start(projectId);
        Optional<ProjectConsoleService.ConsoleSession> second = service.start(projectId);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().sessionId()).matches("^" + projectId + "-console-[0-9a-f]{8}$");
        assertThat(first.get().workingDirectory()).isEqualTo(workarea.toString());
        // Several consoles side by side (#177): a second open is a new session, never a reuse.
        assertThat(second.get().sessionId()).isNotEqualTo(first.get().sessionId())
                .matches("^" + projectId + "-console-[0-9a-f]{8}$");
    }

    @Test
    void findsNothingBeforeAnySessionHasEverBeenAttachedTo(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.find(projectId, "alice")).isEmpty();
    }

    @Test
    void findsAFamilySessionOnceAttachedAndVisibleToItsOwner(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(projectId, "alice")).map(ProjectConsoleService.ConsoleSession::sessionId)
                .contains(projectId + "-console-0a1b2c3d");
    }

    @Test
    void findsALegacyPreFamilySessionUnchanged(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        // The bare "<projectId>-console" id the pre-#177 code minted, persisted before the upgrade.
        sessionRepository.recordAttach(projectId + "-console", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(projectId, "alice")).map(ProjectConsoleService.ConsoleSession::sessionId)
                .contains(projectId + "-console");
    }

    @Test
    void findsTheMostRecentlyAttachedOfSeveralOpenConsoles(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(projectId, "alice")).map(ProjectConsoleService.ConsoleSession::sessionId)
                .contains(projectId + "-console-bbbbbbbb");
    }

    @Test
    void doesNotFindAnotherUsersSession(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.find(projectId, "alice")).isEmpty();
    }

    @Test
    void listsOpenConsolesOldestFirstWithTheirTimes(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console", dbDir, MIDDLE, null); // legacy id, unclaimed
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.listOpen(projectId, "alice"))
                .extracting(ProjectConsoleService.OpenConsole::sessionId, ProjectConsoleService.OpenConsole::createdAt)
                .containsExactly(
                        tuple(projectId + "-console-aaaaaaaa", EARLIER),
                        tuple(projectId + "-console", MIDDLE),
                        tuple(projectId + "-console-bbbbbbbb", LATER));
    }

    @Test
    void listExcludesOtherUsersOtherProjectsAndIssueSessions(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, EARLIER, "bob");
        sessionRepository.recordAttach((projectId + 1) + "-console-cccccccc", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-174-some-worktree", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach("main", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.listOpen(projectId, "alice"))
                .extracting(ProjectConsoleService.OpenConsole::sessionId)
                .containsExactly(projectId + "-console-aaaaaaaa");
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
    void closingAnotherUsersSessionIsRefusedAndLeavesItRunning(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "bob");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.close(projectId, "alice")).isFalse();
        assertThat(sessionRepository.find(projectId + "-console-0a1b2c3d")).isPresent();
    }

    @Test
    void closingTheOwnersCurrentSessionRemovesItsRecord(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-0a1b2c3d", dbDir, EARLIER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.close(projectId, "alice")).isTrue();
        assertThat(sessionRepository.find(projectId + "-console-0a1b2c3d")).isEmpty();
    }

    @Test
    void closingOneSpecificConsoleLeavesItsSiblingsOpen(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach(projectId + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, LATER, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.close(projectId, projectId + "-console-aaaaaaaa", "alice")).isTrue();
        assertThat(sessionRepository.find(projectId + "-console-aaaaaaaa")).isEmpty();
        assertThat(sessionRepository.find(projectId + "-console-bbbbbbbb")).isPresent();
    }

    @Test
    void closingBySessionIdRefusesIdsOutsideTheProjectsFamilyOrAnotherUsers(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        sessionRepository.recordAttach((projectId + 1) + "-console-aaaaaaaa", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-174-some-worktree", dbDir, EARLIER, "alice");
        sessionRepository.recordAttach(projectId + "-console-bbbbbbbb", dbDir, EARLIER, "bob");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRepository);

        // Another project's console, reached through this project's id.
        assertThat(service.close(projectId, (projectId + 1) + "-console-aaaaaaaa", "alice")).isFalse();
        // An issue session is never a project console, whatever the caller claims.
        assertThat(service.close(projectId, projectId + "-174-some-worktree", "alice")).isFalse();
        // Someone else's console.
        assertThat(service.close(projectId, projectId + "-console-bbbbbbbb", "alice")).isFalse();
        // One that was never attached to.
        assertThat(service.close(projectId, projectId + "-console-cccccccc", "alice")).isFalse();
        assertThat(sessionRepository.findAll()).hasSize(3);
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
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.environmentFor(projectId + "-console-0a1b2c3d")).isEmpty();
    }

    @Test
    void environmentForAConsoleSessionDecryptsTheStoredToken(@TempDir Path dbDir) throws IOException {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        TokenCipher tokenCipher = tokenCipher(dbDir);
        projectRepository.setGithubToken(projectId, tokenCipher.encrypt("ghp_realtoken"));
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectConsoleService service = new ProjectConsoleService(projectRepository, tokenCipher,
                new SessionRegistry(sessionRepository), sessionRepository);

        // Both the #177 family and the legacy pre-#177 id resolve the token.
        assertThat(service.environmentFor(projectId + "-console-0a1b2c3d"))
                .isEqualTo(Map.of("GH_TOKEN", "ghp_realtoken"));
        assertThat(service.environmentFor(projectId + "-console")).isEqualTo(Map.of("GH_TOKEN", "ghp_realtoken"));
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
                sessionRepository);
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
