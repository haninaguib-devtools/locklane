package dev.locklane.engine.codeserver;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeServerServiceTest {

    private static final Path BINARY = Path.of("/opt/code-server/bin/code-server");

    @Test
    void startsCodeServerBoundToLoopbackAtTheConsolesWorktree(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    return new ProcessBuilder("true").start();
                });

        var upstream = service.start("1-174-rename-toggle");

        // The loopback base the engine's proxy forwards to (#655) -- never handed to
        // a browser as such; ConsolesController maps it to the proxied path.
        assertThat(upstream).isPresent();
        assertThat(upstream.get().toString()).matches("http://127\\.0\\.0\\.1:\\d+");
        assertThat(invocations).hasSize(1);
        String[] command = invocations.get(0);
        assertThat(command[0]).isEqualTo(BINARY.toString());
        assertThat(command).contains("--bind-addr", "--auth", "none", worktree.toString());
        assertThat(String.join(" ", command)).contains("127.0.0.1:");
    }

    @Test
    void reusesTheAlreadyRunningProcessForASecondStart(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    return new ProcessBuilder("true").start();
                });

        var first = service.start("1-174-rename-toggle");
        var second = service.start("1-174-rename-toggle");

        assertThat(second).isEqualTo(first);
        assertThat(invocations).hasSize(1);
    }

    @Test
    void returnsEmptyAndNeverStartsForAnUnknownConsoleId(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        List<String[]> invocations = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    return new ProcessBuilder("true").start();
                });

        var upstream = service.start("no-such-console");

        assertThat(upstream).isEmpty();
        assertThat(invocations).isEmpty();
    }

    @Test
    void upstreamAnswersOnlyForARunningProcessAndNeverStartsOne(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
                    invocations.add(command);
                    return new ProcessBuilder("true").start();
                });

        // A console that exists but whose IDE nobody asked to open (#655): the proxy
        // resolves nothing, and asking did not spawn anything.
        assertThat(service.upstream("1-174-rename-toggle")).isEmpty();
        assertThat(invocations).isEmpty();

        var started = service.start("1-174-rename-toggle");

        assertThat(service.upstream("1-174-rename-toggle")).isEqualTo(started);
        service.stop("1-174-rename-toggle");
        assertThat(service.upstream("1-174-rename-toggle")).isEmpty();
    }

    @Test
    void stopIsANoOpForAConsoleWithNothingRunning(@TempDir Path dbDir) {
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
        CodeServerService service = new CodeServerService(registry, BINARY,
                command -> {
                    // Sleeps well past this test's lifetime, so a leftover destroy() is
                    // exercised for real rather than racing an already-exited process.
                    Process process = new ProcessBuilder("sleep", "30").start();
                    spawned.add(process);
                    return process;
                });

        service.start("1-174-rename-toggle");
        registry.close("1-174-rename-toggle");

        assertThat(spawned).hasSize(1);
        boolean exited = spawned.get(0).waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exited).isTrue();
        assertThat(service.start("1-174-rename-toggle")).isEmpty(); // the session's record is gone too
    }

    @Test
    void stopAllEndsEveryRunningCodeServerAtShutdown(@TempDir Path dbDir) throws Exception {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-201-one", dbDir.resolve("wt1"), Instant.now(), "alice");
        repository.recordAttach("1-202-two", dbDir.resolve("wt2"), Instant.now(), "alice");
        List<Process> spawned = new ArrayList<>();
        CodeServerService service = new CodeServerService(new SessionRegistry(repository), BINARY,
                command -> {
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
            assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        for (ProcessHandle descendant : descendants) {
            assertThat(descendant.isAlive()).as("descendant %d", descendant.pid()).isFalse();
        }
        assertThat(service.upstream("1-201-one")).isEmpty();
    }
}
