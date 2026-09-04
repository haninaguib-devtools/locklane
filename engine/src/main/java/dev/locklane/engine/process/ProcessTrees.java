package dev.locklane.engine.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Ends whole process trees, not just their roots (#678). The engine spawns console
 * shells, whatever runs inside them, and code-server; on Linux systemd's control
 * group sweeps all of that up when the JVM stops, but on macOS launchd signals only
 * the JVM and everything it spawned lives on. So shutdown ends each tree itself:
 * every descendant is collected <em>before</em> anything is signalled (an orphan is
 * re-parented and no longer findable from its late parent), all of them are asked to
 * stop, and whatever is still alive after the grace period is killed.
 */
public final class ProcessTrees {

    private static final Logger log = LoggerFactory.getLogger(ProcessTrees.class);

    /** How long a forced kill is given to take before the survivors are reported. */
    private static final Duration FORCE_WAIT = Duration.ofSeconds(2);

    private ProcessTrees() {
    }

    /**
     * Terminates {@code roots} and all their descendants: {@code destroy()} (SIGTERM)
     * to every process in the trees, a wait of up to {@code grace} for all of them to
     * exit, then {@code destroyForcibly()} (SIGKILL) to any still alive and a short
     * further wait. Returns the handles that are still alive after all that — empty
     * when the trees are gone. Never throws for a process that exited on its own
     * along the way, and never waits unbounded.
     */
    public static List<ProcessHandle> terminate(Collection<ProcessHandle> roots, Duration grace) {
        Set<ProcessHandle> all = new LinkedHashSet<>();
        for (ProcessHandle root : roots) {
            if (root == null) {
                continue;
            }
            all.add(root);
            root.descendants().forEach(all::add);
        }
        all.forEach(ProcessHandle::destroy);
        awaitExit(all, grace);

        List<ProcessHandle> survivors = alive(all);
        if (!survivors.isEmpty()) {
            survivors.forEach(ProcessHandle::destroyForcibly);
            awaitExit(survivors, FORCE_WAIT);
        }
        return alive(all);
    }

    private static void awaitExit(Collection<ProcessHandle> handles, Duration limit) {
        CompletableFuture<?>[] exits = handles.stream()
                .map(ProcessHandle::onExit)
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(exits).get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // silent: a timeout is the expected outcome for a process that ignores the
            // signal, and alive() is what reports it; the survivors are logged by the caller.
        } catch (InterruptedException e) {
            // silent: the interrupt is re-raised for the caller; nothing here is lost.
            Thread.currentThread().interrupt();
        }
    }

    private static List<ProcessHandle> alive(Collection<ProcessHandle> handles) {
        List<ProcessHandle> left = new ArrayList<>();
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) {
                left.add(handle);
            }
        }
        return left;
    }
}
