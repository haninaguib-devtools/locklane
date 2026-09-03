package dev.locklane.engine.codeserver;

import dev.locklane.engine.persistence.IssueWorktreeService;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.WorktreeSessionAuthorization;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy's admission decision (#655) is the start endpoint's own visibility rule
 * plus "and its IDE is running" — checked here against real SQLite-backed ownership,
 * the same fixtures {@code ConsolesControllerTest} uses.
 */
class CodeServerProxyAuthorizationTest {

    @Test
    void resolvesTheRunningUpstreamForTheProjectsOwnerOnly(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        CodeServerService codeServer = codeServerService(repository);
        CodeServerProxyAuthorization authorization =
                new CodeServerProxyAuthorization(worktreeService(dbDir, repository), codeServer);
        IdeProxyPath path = new IdeProxyPath(1, "1-174-rename-toggle", "/");

        // Nothing running yet: even the owner resolves nothing, and nothing was started.
        assertThat(authorization.upstreamFor(path, "alice")).isEmpty();

        var started = codeServer.start("1-174-rename-toggle");

        assertThat(authorization.upstreamFor(path, "alice")).isEqualTo(started);
        assertThat(authorization.upstreamFor(path, "bob")).isEmpty();
        assertThat(authorization.upstreamFor(path, "nobody")).isEmpty();
        assertThat(authorization.upstreamFor(path, null)).isEmpty();
        // The same console named under the wrong project is not that project's.
        assertThat(authorization.upstreamFor(new IdeProxyPath(2, "1-174-rename-toggle", "/"), "bob")).isEmpty();
    }

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

    private static CodeServerService codeServerService(WorktreeSessionRepository repository) {
        return new CodeServerService(new SessionRegistry(repository), Path.of("/unused/code-server"),
                command -> new ProcessBuilder("true").start());
    }
}
