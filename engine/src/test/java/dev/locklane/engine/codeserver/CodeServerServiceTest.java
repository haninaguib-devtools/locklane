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

        var url = service.start("1-174-rename-toggle");

        assertThat(url).isPresent();
        assertThat(url.get()).matches("http://127\\.0\\.0\\.1:\\d+/");
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

        var url = service.start("no-such-console");

        assertThat(url).isEmpty();
        assertThat(invocations).isEmpty();
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
}
