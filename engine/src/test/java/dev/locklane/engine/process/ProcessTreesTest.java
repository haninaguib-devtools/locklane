package dev.locklane.engine.process;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #678: ending a process tree means the descendants too, not only the root the
 * engine started — collected before the root is signalled, since an orphan is
 * re-parented and no longer findable from its late parent.
 */
class ProcessTreesTest {

    @Test
    void endsTheRootAndEveryDescendant() throws Exception {
        // A shell with a background child it waits on: the child is a grandchild of
        // this JVM, exactly the shape a console shell running an agent has.
        Process root = new ProcessBuilder("/bin/sh", "-c", "sleep 300 & wait").start();
        ProcessHandle rootHandle = root.toHandle();
        List<ProcessHandle> children = waitForDescendants(rootHandle);
        assertThat(children).isNotEmpty();

        List<ProcessHandle> left = ProcessTrees.terminate(List.of(rootHandle), Duration.ofSeconds(2));

        assertThat(left).isEmpty();
        assertThat(root.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        for (ProcessHandle child : children) {
            assertThat(child.isAlive()).as("descendant %d still alive", child.pid()).isFalse();
        }
    }

    @Test
    void forcesAProcessThatIgnoresTheFirstSignal() throws Exception {
        // trap '' TERM: the shell ignores SIGTERM, so only the forced kill ends it.
        Process root = new ProcessBuilder("/bin/sh", "-c", "trap '' TERM; sleep 300 & wait").start();
        ProcessHandle rootHandle = root.toHandle();
        waitForDescendants(rootHandle);

        List<ProcessHandle> left = ProcessTrees.terminate(List.of(rootHandle), Duration.ofMillis(500));

        assertThat(left).isEmpty();
        assertThat(root.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anAlreadyExitedRootIsNotAnError() throws Exception {
        Process root = new ProcessBuilder("/bin/true").start();
        root.waitFor();

        assertThat(ProcessTrees.terminate(List.of(root.toHandle()), Duration.ofSeconds(1))).isEmpty();
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
