package dev.locklane.engine.codeserver;

import dev.locklane.engine.persistence.IssueWorktreeService;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.WorktreeSessionAuthorization;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy's admission decision (#655) is the start endpoint's own visibility rule
 * plus "and its IDE is running" — checked here against real SQLite-backed ownership,
 * the same fixtures {@code AgentSessionsControllerTest} uses.
 */
class CodeServerProxyAuthorizationTest {

    /**
     * Stub listeners {@link #codeServerService} binds, kept alive for the class's
     * whole run (#776): an unreferenced {@link ServerSocket} is eligible for the JVM
     * to reclaim -- and close -- before {@code CodeServerService.start}'s own wait for
     * a connection gets to it.
     */
    private static final List<ServerSocket> CODE_SERVER_STUBS = new CopyOnWriteArrayList<>();

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
        // The same agent session named under the wrong project is not that project's.
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

    /** A stub listener on the port named by {@code --bind-addr} so {@code start()}'s own wait for a connection succeeds (#776). */
    private static CodeServerService codeServerService(WorktreeSessionRepository repository) {
        return new CodeServerService(new SessionRegistry(repository), Path.of("/unused/code-server"),
                command -> {
                    for (String arg : command) {
                        if (arg.startsWith("127.0.0.1:")) {
                            CODE_SERVER_STUBS.add(new ServerSocket(Integer.parseInt(arg.substring("127.0.0.1:".length()))));
                            break;
                        }
                    }
                    return new ProcessBuilder("true").start();
                });
    }
}
