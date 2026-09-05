package dev.locklane.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class InstalledAgentsStoreTest {

    @Test
    void startsEmptyBeforeTheBootstrapperRuns() {
        assertThat(new InstalledAgentsStore().installed()).isEmpty();
    }

    @Test
    void reflectsWhatWasSetInDetectionOrder() {
        InstalledAgentsStore store = new InstalledAgentsStore();

        store.set(Set.of("opencode", "claude"));

        assertThat(store.installed()).containsExactly(new AgentInfo("claude", "Claude"),
                new AgentInfo("opencode", "OpenCode"));
    }

    @Test
    void knownAgentsIncludesOmp() {
        assertThat(InstalledAgentsStore.KNOWN_AGENTS).containsExactly(new AgentInfo("claude", "Claude"),
                new AgentInfo("codex", "Codex"), new AgentInfo("opencode", "OpenCode"), new AgentInfo("omp", "OMP"));
    }

    @Test
    void labelForReturnsTheKnownLabel() {
        assertThat(new InstalledAgentsStore().labelFor("omp")).isEqualTo("OMP");
    }

    @Test
    void labelForFallsBackToTheIdItselfForAnUnknownAgent() {
        assertThat(new InstalledAgentsStore().labelFor("gpt-5")).isEqualTo("gpt-5");
    }
}
