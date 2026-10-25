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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Covers #1014's done-when: on Linux a session's process, and every process it starts,
 * runs at {@link PtySession#SESSION_OOM_SCORE_ADJ}, above the engine's own score, so the
 * kernel's OOM killer picks a session's process before the engine; elsewhere, or when the
 * program is not an executable here, the command is spawned as before.
 */
class PtySessionOomScoreTest {

    @Test
    void leavesTheCommandUnchangedWhereTheKernelHasNoOomScore(@TempDir Path workDir) throws IOException {
        Path bin = agentOnPath(workDir);
        String[] command = {"agent", "--resume", "abc"};

        assertThat(PtySession.withRaisedOomScore(command, false, workDir, bin.toString())).containsExactly(command);
    }

    @Test
    void wrapsTheCommandInAShellThatRaisesTheScoreThenExecsIt(@TempDir Path workDir) throws IOException {
        Path bin = agentOnPath(workDir);
        String[] wrapped = PtySession.withRaisedOomScore(new String[] {"agent", "--resume", "abc"}, true, workDir,
                bin.toString());

        assertThat(wrapped).hasSize(7);
        assertThat(wrapped[0]).isEqualTo("/bin/sh");
        assertThat(wrapped[1]).isEqualTo("-c");
        assertThat(wrapped[2]).contains("echo " + PtySession.SESSION_OOM_SCORE_ADJ + " > /proc/self/oom_score_adj")
                .endsWith("exec \"$@\"");
        assertThat(wrapped).endsWith("agent", "--resume", "abc");
    }

    @Test
    void wrapsAnAbsoluteOrWorkingDirectoryRelativeProgram(@TempDir Path workDir) throws IOException {
        Path bin = agentOnPath(workDir);
        String absolute = bin.resolve("agent").toString();

        assertThat(PtySession.withRaisedOomScore(new String[] {absolute}, true, workDir, null)).endsWith(absolute);
        assertThat(PtySession.withRaisedOomScore(new String[] {absolute}, true, workDir, null)[0]).isEqualTo("/bin/sh");
        assertThat(PtySession.withRaisedOomScore(new String[] {"./bin/agent"}, true, workDir, null)[0])
                .isEqualTo("/bin/sh");
    }

    @Test
    void leavesAProgramThatIsNotExecutableHereUnwrappedSoItStillFailsToStart(@TempDir Path workDir)
            throws IOException {
        Path bin = agentOnPath(workDir);
        String[] missing = {"no-such-agent", "--resume", "abc"};

        assertThat(PtySession.withRaisedOomScore(missing, true, workDir, bin.toString())).containsExactly(missing);
        assertThatThrownBy(() -> new PtySession("oom-score-missing", workDir, missing, Map.of("PATH", bin.toString()),
                80, 24)).isInstanceOf(PtySessionStartException.class);
    }

    @Test
    void theSessionsProcessAndItsChildrenRunAtTheRaisedScore(@TempDir Path workDir) {
        assumeTrue(Files.exists(Path.of("/proc/self/oom_score_adj")), "no per-process OOM score on this kernel");
        // $$ is the session's own process (the wrapper exec'd into this sh); the inner
        // sh is a child it starts, which must inherit the score.
        PtySession session = new PtySession("oom-score", workDir,
                new String[] {"/bin/sh", "-c",
                        "echo self=$(cat /proc/$$/oom_score_adj); /bin/sh -c 'echo child=$(cat /proc/$$/oom_score_adj)'"},
                Map.of(), 80, 24);

        waitUntil(() -> session.bufferedOutput().contains("child="), Duration.ofSeconds(5));
        assertThat(session.bufferedOutput())
                .contains("self=" + PtySession.SESSION_OOM_SCORE_ADJ)
                .contains("child=" + PtySession.SESSION_OOM_SCORE_ADJ);
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
