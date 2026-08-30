package dev.locklane.engine.agent;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Answers "is this CLI on the host `PATH`" by scanning `PATH` directories for an
 * executable file named after the CLI (#359) — no process spawned per candidate,
 * just a directory listing, so it is cheap enough to run once at boot for every
 * supported agent.
 */
final class InstalledAgentDetector {

    private InstalledAgentDetector() {
    }

    /** {@code candidates} in the order the result should preserve. */
    static Set<String> detect(String pathEnv, String[] candidates) {
        Set<String> found = new LinkedHashSet<>();
        if (pathEnv == null || pathEnv.isBlank()) {
            return found;
        }
        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String candidate : candidates) {
            for (String dir : dirs) {
                if (dir.isBlank()) {
                    continue;
                }
                File file = new File(dir, candidate);
                if (file.isFile() && file.canExecute()) {
                    found.add(candidate);
                    break;
                }
            }
        }
        return found;
    }
}
