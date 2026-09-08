package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectIdeSessionServiceTest {

    @Test
    void openingOnAnUnknownProjectIsEmpty(@TempDir Path dbDir) {
        ProjectIdeSessionService service = service(dbDir, TestSqliteDatabases.newProjectRepository(dbDir),
                TestSqliteDatabases.newRepository(dbDir));

        assertThat(service.open(999, "alice")).isEmpty();
    }

    @Test
    void openingOnAProjectStillCloningIsEmpty(@TempDir Path dbDir) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository.create("proj", "url", dbDir.resolve("work"), 1L, Instant.now()).id();
        ProjectIdeSessionService service = service(dbDir, projectRepository, TestSqliteDatabases.newRepository(dbDir));

        assertThat(service.open(projectId, "alice")).isEmpty();
    }

    @Test
    void openingMintsTheCanonicalIdAtTheProjectsWorkarea(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        Path workarea = dbDir.resolve("work");
        long projectId = projectRepository.createReady("proj", "url", workarea, "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectIdeSessionService service = service(dbDir, projectRepository, sessionRepository);

        ProjectIdeSessionService.ProjectIdeSession session = service.open(projectId, "alice").orElseThrow();

        assertThat(session.sessionId()).isEqualTo(projectId + "-ide-main");
        assertThat(session.workingDirectory()).isEqualTo(workarea.toString());
        assertThat(sessionRepository.find(session.sessionId()))
                .map(WorktreeSessionRecord::workingDirectory).contains(workarea);
    }

    @Test
    void openingTwiceReusesTheSameRowRatherThanMintingAnother(@TempDir Path dbDir) {
        // Unlike a shell (#444), a single IDE at the main checkout is meant to be
        // reused across opens, exactly as CodeServerService.start already reuses a
        // running process for a session id it has seen before.
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectIdeSessionService service = service(dbDir, projectRepository, sessionRepository);

        ProjectIdeSessionService.ProjectIdeSession first = service.open(projectId, "alice").orElseThrow();
        ProjectIdeSessionService.ProjectIdeSession second = service.open(projectId, "alice").orElseThrow();

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(sessionRepository.findAll()).hasSize(1);
    }

    @Test
    void openingAsANonOwnerIsEmptyAndPersistsNothing(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectIdeSessionService service = service(dbDir, projectRepository, sessionRepository);

        assertThat(service.open(projectId, "bob")).isEmpty();

        assertThat(sessionRepository.findAll()).isEmpty();
    }

    @Test
    void isOpenAndVisibleToIsFalseBeforeOpeningAndForAnotherProjectOrOwner(@TempDir Path dbDir) {
        long aliceId = createUser(dbDir, "alice");
        createUser(dbDir, "bob");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectIdeSessionService service = service(dbDir, projectRepository, sessionRepository);
        String sessionId = projectId + "-ide-main";

        // Never opened yet: no row, so nothing is visible.
        assertThat(service.isOpenAndVisibleTo(projectId, sessionId, "alice")).isFalse();

        service.open(projectId, "alice");

        assertThat(service.isOpenAndVisibleTo(projectId, sessionId, "alice")).isTrue();
        // A non-owner never sees it, even once it exists (#242, #394).
        assertThat(service.isOpenAndVisibleTo(projectId, sessionId, "bob")).isFalse();
        // Neither an id for a different project nor an ordinary agent session id counts.
        assertThat(service.isOpenAndVisibleTo(999, sessionId, "alice")).isFalse();
        assertThat(service.isOpenAndVisibleTo(projectId, projectId + "-7-do-the-thing", "alice")).isFalse();
    }

    @Test
    void isExcludedFromTheGeneralAgentSessionListingsAndFromTheProjectDeleteRefusal(@TempDir Path dbDir) {
        // Unlike a shell, this session has no explicit close: counting it in
        // hasAnySessions would block a project delete forever the first time its IDE
        // was ever opened, so it stays out of that sweep on purpose (#831).
        long aliceId = createUser(dbDir, "alice");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(dbDir);
        long projectId = projectRepository
                .createReady("proj", "url", dbDir.resolve("work"), "main", aliceId, Instant.now()).id();
        WorktreeSessionRepository sessionRepository = TestSqliteDatabases.newRepository(dbDir);
        ProjectIdeSessionService service = service(dbDir, projectRepository, sessionRepository);
        service.open(projectId, "alice");
        IssueWorktreeService issueWorktreeService = new IssueWorktreeService(sessionRepository,
                new WorktreeSessionAuthorization(projectRepository, TestSqliteDatabases.newUserRepository(dbDir)));

        assertThat(issueWorktreeService.allWorktreeIds(projectId, "alice")).isEmpty();
        assertThat(issueWorktreeService.hasAnySessions(projectId)).isFalse();
    }

    private static ProjectIdeSessionService service(Path dbDir, ProjectRepository projectRepository,
            WorktreeSessionRepository sessionRepository) {
        return new ProjectIdeSessionService(projectRepository, sessionRepository,
                new WorktreeSessionAuthorization(projectRepository, TestSqliteDatabases.newUserRepository(dbDir)));
    }

    private static long createUser(Path dbDir, String username) {
        return TestSqliteDatabases.newUserRepository(dbDir).create(username, "bcrypt-hash", Instant.now()).id();
    }
}
