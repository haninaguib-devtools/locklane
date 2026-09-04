package dev.locklane.engine.agent;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Holds which of the four supported CLIs (#359, #681) {@link InstalledAgentsBootstrapper}
 * found on the host `PATH` at startup. Detection runs once at boot (issue Non-goals) —
 * this store never re-probes, so its value is fixed for the process's lifetime once
 * {@link #set} has run.
 */
@Component
public class InstalledAgentsStore {

    static final String CLAUDE = "claude";
    static final String CODEX = "codex";
    static final String OPENCODE = "opencode";
    static final String OMP = "omp";
    static final String[] KNOWN_AGENTS = {CLAUDE, CODEX, OPENCODE, OMP};

    private volatile Set<String> installed = Set.of();

    void set(Set<String> installed) {
        this.installed = installed;
    }

    public Set<String> installed() {
        return installed;
    }
}
