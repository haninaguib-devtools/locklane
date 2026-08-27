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

class ProjectConsoleServiceTest {

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
    void startingOnAReadyProjectMintsADeterministicSessionId(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        Path workarea = dbDir.resolve("work");
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        Optional<ProjectConsoleService.ConsoleSession> first = service.start(projectId);
        Optional<ProjectConsoleService.ConsoleSession> second = service.start(projectId);

        assertThat(first).map(ProjectConsoleService.ConsoleSession::sessionId).contains(projectId + "-console");
        assertThat(first).map(ProjectConsoleService.ConsoleSession::workingDirectory).contains(workarea.toString());
        assertThat(second).isEqualTo(first);
    }

    @Test
    void findsNothingBeforeTheSessionHasEverBeenAttachedTo(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        ProjectConsoleService service = service(dbDir, projectRepository);

        assertThat(service.find(projectId, "alice")).isEmpty();
    }

    @Test
    void findsTheSessionOnceAttachedAndVisibleToItsOwner(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(sessionRepository);
        sessionRegistry.attach(projectId + "-console", dbDir, null, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRegistry);

        assertThat(service.find(projectId, "alice")).map(ProjectConsoleService.ConsoleSession::sessionId)
                .contains(projectId + "-console");
    }

    @Test
    void doesNotFindAnotherUsersSession(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(sessionRepository);
        sessionRegistry.attach(projectId + "-console", dbDir, null, "bob");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRegistry);

        assertThat(service.find(projectId, "alice")).isEmpty();
    }

    @Test
    void closingAnUnknownSessionDoesNothingAndReportsFalse(@TempDir Path dbDir) {
        ProjectConsoleService service = service(dbDir);

        assertThat(service.close(1, "alice")).isFalse();
    }

    @Test
    void closingAnotherUsersSessionIsRefusedAndLeavesItRunning(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(sessionRepository);
        sessionRegistry.attach(projectId + "-console", dbDir, null, "bob");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRegistry);

        assertThat(service.close(projectId, "alice")).isFalse();
        assertThat(sessionRepository.find(projectId + "-console")).isPresent();
    }

    @Test
    void closingTheOwnersSessionRemovesItsRecord(@TempDir Path dbDir) {
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        SessionRegistry sessionRegistry = new SessionRegistry(sessionRepository);
        sessionRegistry.attach(projectId + "-console", dbDir, null, "alice");
        ProjectConsoleService service = service(dbDir, projectRepository, sessionRegistry);

        assertThat(service.close(projectId, "alice")).isTrue();
        assertThat(sessionRepository.find(projectId + "-console")).isEmpty();
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

        assertThat(service.environmentFor(projectId + "-console")).isEmpty();
    }

    @Test
    void environmentForAConsoleSessionDecryptsTheStoredToken(@TempDir Path dbDir) throws IOException {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.createReady("proj", "url", dbDir.resolve("work"), "main", Instant.now()).id();
        TokenCipher tokenCipher = tokenCipher(dbDir);
        projectRepository.setGithubToken(projectId, tokenCipher.encrypt("ghp_realtoken"));
        ProjectConsoleService service = new ProjectConsoleService(projectRepository, tokenCipher,
                new SessionRegistry(TestSqliteDatabases.newRepository(dbDir)));

        assertThat(service.environmentFor(projectId + "-console")).isEqualTo(Map.of("GH_TOKEN", "ghp_realtoken"));
    }

    private static ProjectConsoleService service(Path dbDir) {
        return service(dbDir, TestSqliteDatabases.newProjectRepository(dbDir));
    }

    private static ProjectConsoleService service(Path dbDir, ProjectRepository projectRepository) {
        return service(dbDir, projectRepository, new SessionRegistry(TestSqliteDatabases.newRepository(dbDir)));
    }

    private static ProjectConsoleService service(Path dbDir, ProjectRepository projectRepository,
            SessionRegistry sessionRegistry) {
        return new ProjectConsoleService(projectRepository, tokenCipher(dbDir), sessionRegistry);
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
