package dev.locklane.engine.agent;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single table of id + label per known coding-agent CLI (#359, #681, #695) — the
 * only place that pairing is written down; {@link InstalledAgentsController} and every
 * label a client sees (the settings picker, a resumed session's tool) come from here.
 * Also holds which of {@link #KNOWN_AGENTS} {@link InstalledAgentsBootstrapper} found on
 * the host `PATH` at startup. Detection runs once at boot (issue Non-goals) — this store
 * never re-probes, so its installed set is fixed for the process's lifetime once
 * {@link #set} has run.
 */
@Component
public class InstalledAgentsStore {

    static final List<AgentInfo> KNOWN_AGENTS = List.of(
            new AgentInfo("claude", "Claude"),
            new AgentInfo("codex", "Codex"),
            new AgentInfo("opencode", "OpenCode"),
            new AgentInfo("omp", "OMP"));

    private volatile Set<String> installedIds = Set.of();

    void set(Set<String> installedIds) {
        this.installedIds = installedIds;
    }

    /** The installed subset of {@link #KNOWN_AGENTS}, in detection order. */
    public List<AgentInfo> installed() {
        return KNOWN_AGENTS.stream().filter(agent -> installedIds.contains(agent.id())).toList();
    }

    /**
     * The display label for any known agent id; falls back to the id itself for one
     * this store does not know (a captured session can name a tool that predates this
     * store, or one removed from {@link #KNOWN_AGENTS} since) rather than failing.
     */
    public String labelFor(String id) {
        return KNOWN_AGENTS.stream().filter(agent -> agent.id().equals(id)).map(AgentInfo::label).findFirst()
                .orElse(id);
    }
}
