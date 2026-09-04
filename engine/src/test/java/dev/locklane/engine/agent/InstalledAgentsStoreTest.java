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
    void reflectsWhatWasSet() {
        InstalledAgentsStore store = new InstalledAgentsStore();

        store.set(Set.of("claude", "codex"));

        assertThat(store.installed()).containsExactlyInAnyOrder("claude", "codex");
    }

    @Test
    void knownAgentsIncludesOmp() {
        assertThat(InstalledAgentsStore.KNOWN_AGENTS).containsExactly("claude", "codex", "opencode", "omp");
    }
}
