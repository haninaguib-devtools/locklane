package dev.locklane.engine.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers #1015's done-when: with {@code systemd-run} available, a session's process tree
 * runs in its own scope under {@link SessionScope#SLICE}, still at the raised OOM score
 * (#1014), and the engine still sees it exit; without it, or for a program that is not an
 * executable here, the command is spawned as before.
 */
class PtySessionScopeTest {

    @Test
    void theScopePrefixNamesTheSessionsSlice() {
        assertThat(SessionScope.prefix("/usr/bin/systemd-run", "project-3-agent-1")).containsExactly(
                "/usr/bin/systemd-run", "--user", "--scope", "--quiet", "--collect",
                "--slice=locklane-sessions.slice", "--description=locklane session project-3-agent-1");
    }

    @Test
    void aSessionWithoutTheUserManagersRuntimeDirectoryGetsNoScope() {
        assertThat(SessionScope.prefix("s1", Map.of("TERM", "xterm-256color"))).isEmpty();
        assertThat(SessionScope.prefix("s1", Map.of("XDG_RUNTIME_DIR", " "))).isEmpty();
    }

    @Test
    void wrapsTheCommandInItsScopeBehindTheOomScoreWrapper(@TempDir Path workDir) throws IOException {
        Path bin = agentOnPath(workDir);
        String[] scope = SessionScope.prefix("/usr/bin/systemd-run", "s1");

        String[] spawned = PtySession.spawnCommand(new String[] {"agent", "--resume", "abc"}, workDir, bin.toString(),
                true, scope);

        // The score is raised first, so the scope's systemd-run -- and everything it execs -- inherits it.
        assertThat(spawned[0]).isEqualTo("/bin/sh");
        assertThat(spawned[3]).isEqualTo("locklane-session");
        assertThat(spawned[4]).isEqualTo("/usr/bin/systemd-run");
        assertThat(spawned).endsWith("--description=locklane session s1", "agent", "--resume", "abc");
    }

    @Test
    void scopesTheCommandWithoutTheOomWrapperWhereTheKernelHasNoOomScore(@TempDir Path workDir) throws IOException {
        Path bin = agentOnPath(workDir);
        String[] scope = SessionScope.prefix("/usr/bin/systemd-run", "s1");

        String[] spawned = PtySession.spawnCommand(new String[] {"agent"}, workDir, bin.toString(), false, scope);

        assertThat(spawned[0]).isEqualTo("/usr/bin/systemd-run");
        assertThat(spawned).endsWith("agent");
    }

    @Test
    void leavesAProgramThatIsNotExecutableHereOutOfTheScope(@TempDir Path workDir) throws IOException {
        Path bin = agentOnPath(workDir);
        String[] missing = {"no-such-agent"};

        assertThat(PtySession.spawnCommand(missing, workDir, bin.toString(), true,
                SessionScope.prefix("/usr/bin/systemd-run", "s1"))).containsExactly(missing);
    }

    @Test
    void aSessionRunsInItsOwnScopeUnderTheSlice(@TempDir Path workDir) {
        // The engine's own environment, as every real session gets.
        Map<String, String> environment = System.getenv();
        assumeTrue(SessionScope.prefix("scope-probe", environment).length > 0,
                "systemd-run cannot start a user scope here");
        PtySession session = new PtySession("scope-live", workDir,
                new String[] {"/bin/sh", "-c", "echo cgroup=$(cat /proc/self/cgroup) score=$(cat /proc/self/oom_score_adj)"},
                environment, 200, 24);

        waitUntil(() -> session.bufferedOutput().contains("score="), Duration.ofSeconds(10));
        assertThat(session.bufferedOutput())
                .contains("/" + SessionScope.SLICE + "/")
                .containsPattern("cgroup=\\S*\\.scope")
                .contains("score=" + PtySession.SESSION_OOM_SCORE_ADJ);
        // The scope's process is the session's own pid, so its exit is still the session's.
        waitUntil(() -> !session.isAlive(), Duration.ofSeconds(10));
    }

    /** A {@code bin/agent} executable under {@code workDir}; returns the {@code bin} directory. */
    private static Path agentOnPath(Path workDir) throws IOException {
        Path bin = Files.createDirectories(workDir.resolve("bin"));
        Path agent = Files.writeString(bin.resolve("agent"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(agent, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    private static void waitUntil(Supplier<Boolean> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }
}
