package dev.locklane.engine.codeserver;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeServerServiceTest {

    private static final Path BINARY = Path.of("/opt/code-server/bin/code-server");

    /** Extracts the port a spawned command's {@code --bind-addr 127.0.0.1:<port>} names. */
    private static int portFrom(String[] command) {
        for (String arg : command) {
            if (arg.startsWith("127.0.0.1:")) {
                return Integer.parseInt(arg.substring("127.0.0.1:".length()));
            }
        }
        throw new IllegalStateException("no 127.0.0.1:<port> token in " + java.util.Arrays.toString(command));
    }

    /**
     * Binds a stub listener at the port {@code command} names, standing in for
     * code-server actually coming up (#776) so {@code start()}'s own wait for a
     * connection succeeds. Added to {@code stubs} rather than returned bare -- an
     * unreferenced {@link ServerSocket} is eligible for the JVM to reclaim (and close)
     * before {@code start()}'s polling loop gets to it, a real flake this test class
     * hit once already.
     */
    private static void listenOn(String[] command, List<ServerSocket> stubs) throws IOException {
        stubs.add(new ServerSocket(portFrom(command)));
    }

    private static void closeAll(List<ServerSocket> stubs) throws IOException {
        for (ServerSocket stub : stubs) {
            stub.close();
        }
    }

    @Test
    void startsCodeServerBoundToLoopbackAtTheAgentSessionsWorktree(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        List<ServerSocket> stubs = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    listenOn(command, stubs);
                    return new ProcessBuilder("true").start();
                });

        var upstream = service.start("1-174-rename-toggle");

        // The loopback base the engine's proxy forwards to (#655) -- never handed to
        // a browser as such; AgentSessionsController maps it to the proxied path.
        assertThat(upstream).isPresent();
        assertThat(upstream.get().toString()).matches("http://127\\.0\\.0\\.1:\\d+");
        assertThat(invocations).hasSize(1);
        String[] command = invocations.get(0);
        assertThat(command[0]).isEqualTo(BINARY.toString());
        assertThat(command).contains("--bind-addr", "--auth", "none", "--ignore-last-opened", worktree.toString());
        assertThat(String.join(" ", command)).contains("127.0.0.1:");
        closeAll(stubs);
    }

    @Test
    void reusesTheAlreadyRunningProcessForASecondStart(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        List<ServerSocket> stubs = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    listenOn(command, stubs);
                    return new ProcessBuilder("true").start();
                });

        var first = service.start("1-174-rename-toggle");
        var second = service.start("1-174-rename-toggle");

        assertThat(second).isEqualTo(first);
        assertThat(invocations).hasSize(1);
        closeAll(stubs);
    }

    @Test
    void returnsEmptyAndNeverStartsForAnUnknownAgentSessionId(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        List<String[]> invocations = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    return new ProcessBuilder("true").start();
                });

        var upstream = service.start("no-such-agent-session");

        assertThat(upstream).isEmpty();
        assertThat(invocations).isEmpty();
    }

    @Test
    void upstreamAnswersOnlyForARunningProcessAndNeverStartsOne(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        List<ServerSocket> stubs = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    listenOn(command, stubs);
                    return new ProcessBuilder("true").start();
                });

        // An agent session that exists but whose IDE nobody asked to open (#655): the proxy
        // resolves nothing, and asking did not spawn anything.
        assertThat(service.upstream("1-174-rename-toggle")).isEmpty();
        assertThat(invocations).isEmpty();

        var started = service.start("1-174-rename-toggle");

        assertThat(service.upstream("1-174-rename-toggle")).isEqualTo(started);
        service.stop("1-174-rename-toggle");
        assertThat(service.upstream("1-174-rename-toggle")).isEmpty();
        closeAll(stubs);
    }

    @Test
    void stopIsANoOpForAAgentSessionWithNothingRunning(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> new ProcessBuilder("true").start());

        service.stop("never-started");
        // No exception is the assertion: stop() on an id with nothing running is a no-op.
    }

    @Test
    void closingTheSessionStopsItsCodeServerProcess(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        SessionRegistry registry = new SessionRegistry(repository);
        List<Process> spawned = new ArrayList<>();
        List<ServerSocket> stubs = new ArrayList<>();
        CodeServerService service = new CodeServerService(registry, BINARY,
                command -> {
                    listenOn(command, stubs);
                    // Sleeps well past this test's lifetime, so a leftover destroy() is
                    // exercised for real rather than racing an already-exited process.
                    Process process = new ProcessBuilder("sleep", "30").start();
                    spawned.add(process);
                    return process;
                });

        service.start("1-174-rename-toggle");
        registry.close("1-174-rename-toggle");

        assertThat(spawned).hasSize(1);
        boolean exited = spawned.get(0).waitFor(5, TimeUnit.SECONDS);
        assertThat(exited).isTrue();
        assertThat(service.start("1-174-rename-toggle")).isEmpty(); // the session's record is gone too
        closeAll(stubs);
    }

    @Test
    void closingTheSessionReturnsPromptlyEvenWhenCodeServerIgnoresSigterm(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        SessionRegistry registry = new SessionRegistry(repository);
        List<Process> spawned = new ArrayList<>();
        List<ServerSocket> stubs = new ArrayList<>();
        CodeServerService service = new CodeServerService(registry, BINARY,
                command -> {
                    listenOn(command, stubs);
                    // Ignores SIGTERM and has a child of its own -- the shape that made
                    // stop() block the close listener for up to the grace period plus the
                    // forced-kill wait (#682) before termination moved to a background
                    // thread.
                    Process process = new ProcessBuilder("/bin/sh", "-c", "trap \"\" TERM; sleep 300 & wait").start();
                    spawned.add(process);
                    return process;
                });

        service.start("1-174-rename-toggle");
        List<ProcessHandle> descendants = new ArrayList<>();
        long spawnDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < spawnDeadline && descendants.isEmpty()) {
            descendants = spawned.get(0).toHandle().descendants().toList();
            Thread.sleep(50);
        }
        assertThat(descendants).hasSize(1);

        long start = System.currentTimeMillis();
        registry.close("1-174-rename-toggle");
        long elapsedMillis = System.currentTimeMillis() - start;

        assertThat(elapsedMillis).isLessThan(1000);
        assertThat(spawned.get(0).waitFor(10, TimeUnit.SECONDS)).isTrue();
        for (ProcessHandle descendant : descendants) {
            long deadline = System.currentTimeMillis() + 10000;
            while (descendant.isAlive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(descendant.isAlive()).as("descendant %d", descendant.pid()).isFalse();
        }
        closeAll(stubs);
    }

    @Test
    void stopAllEndsEveryRunningCodeServerAtShutdown(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-201-one", dbDir.resolve("wt1"), Instant.now(), "alice");
        repository.recordAttach("1-202-two", dbDir.resolve("wt2"), Instant.now(), "alice");
        List<Process> spawned = new ArrayList<>();
        List<ServerSocket> stubs = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    listenOn(command, stubs);
                    // A shell with a child, the shape a real code-server has (node plus
                    // its extension host): #678 ends the tree, not only the root.
                    Process process = new ProcessBuilder("/bin/sh", "-c", "sleep 300 & wait").start();
                    spawned.add(process);
                    return process;
                });
        service.start("1-201-one");
        service.start("1-202-two");
        assertThat(spawned).hasSize(2);
        List<ProcessHandle> descendants = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && descendants.size() < 2) {
            descendants = spawned.stream().flatMap(p -> p.toHandle().descendants()).toList();
            Thread.sleep(50);
        }
        assertThat(descendants).hasSize(2);

        service.stopAll();

        for (Process process : spawned) {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
        for (ProcessHandle descendant : descendants) {
            assertThat(descendant.isAlive()).as("descendant %d", descendant.pid()).isFalse();
        }
        assertThat(service.upstream("1-201-one")).isEmpty();
        closeAll(stubs);
    }

    @Test
    void startWaitsForCodeServerToAcceptConnectionsBeforeReturning(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<ServerSocket> stubs = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    // The stub listener stands in for code-server itself binding the
                    // port (#776) -- start() must not return before this is up.
                    listenOn(command, stubs);
                    return new ProcessBuilder("sleep", "30").start();
                });

        var upstream = service.start("1-174-rename-toggle");

        assertThat(upstream).isPresent();
        closeAll(stubs);
    }

    @Test
    void startFailsAndUntracksWhenCodeServerNeverListens(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<Process> spawned = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    // Never binds the port service.start() is waiting on.
                    Process process = new ProcessBuilder("sleep", "30").start();
                    spawned.add(process);
                    return process;
                },
                Duration.ofMillis(300));

        assertThatThrownBy(() -> service.start("1-174-rename-toggle"))
                .isInstanceOf(CodeServerService.CodeServerLaunchException.class);

        // Stopped -- the process that never listened does not outlive the failed start --
        // and untracked, so a later call is a fresh attempt rather than reusing nothing.
        assertThat(spawned.get(0).waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.upstream("1-174-rename-toggle")).isEmpty();
    }
}
