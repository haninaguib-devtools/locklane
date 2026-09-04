package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #678: engine shutdown ends every console's whole process tree, not only the shell.
 * A console shell running an agent or a build is the shape below: a shell with a
 * background child it waits on. On macOS nothing else would ever clean that up.
 */
class SessionRegistryShutdownTest {

    @Test
    void closeAllEndsEveryDescendantOfEverySession(@TempDir Path workDir) throws Exception {
        SessionRegistry registry = new SessionRegistry(TestSqliteDatabases.newRepository(workDir));
        PtySession session = registry.attach("shutdown-a", workDir,
                new String[] {"/bin/sh", "-c", "sleep 300 & wait"});
        ProcessHandle shell = session.processHandle().orElseThrow();
        List<ProcessHandle> children = waitForDescendants(shell);
        assertThat(children).isNotEmpty();

        registry.closeAll();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && (shell.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))) {
            Thread.sleep(50);
        }
        assertThat(shell.isAlive()).as("the session's shell").isFalse();
        for (ProcessHandle child : children) {
            assertThat(child.isAlive()).as("descendant %d of the shell", child.pid()).isFalse();
        }
        assertThat(session.isAlive()).isFalse();
    }

    private static List<ProcessHandle> waitForDescendants(ProcessHandle root) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<ProcessHandle> found = root.descendants().toList();
            if (!found.isEmpty()) {
                return found;
            }
            Thread.sleep(50);
        }
        return List.of();
    }
}
