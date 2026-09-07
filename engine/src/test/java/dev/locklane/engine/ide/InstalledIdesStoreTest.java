package dev.locklane.engine.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InstalledIdesStoreTest {

    @Test
    void startsEmptyBeforeTheBootstrapperRuns() {
        InstalledIdesStore store = new InstalledIdesStore();

        assertThat(store.installed()).isEmpty();
        assertThat(store.find("vscode")).isEmpty();
    }

    @Test
    void reflectsWhatWasSetWithTheTablesLabelsAndDesktopFlags() {
        InstalledIdesStore store = new InstalledIdesStore();
        store.set(List.of(InstalledIde.onPath(KnownIdes.CODE_SERVER.info()),
                new InstalledIde(KnownIdes.find("intellij").orElseThrow().info(), "IntelliJ IDEA CE")));

        assertThat(store.installed()).containsExactly(new IdeInfo("code-server", "code-server", false),
                new IdeInfo("intellij", "IntelliJ IDEA", true));
        assertThat(store.find("intellij")).contains(
                new InstalledIde(new IdeInfo("intellij", "IntelliJ IDEA", true), "IntelliJ IDEA CE"));
        assertThat(store.find("vscode")).isEmpty();
    }

    @Test
    void theTableIsCodeServerThenVsCodeThenIntelliJ() {
        assertThat(KnownIdes.TABLE).extracting(KnownIdes.Definition::info).containsExactly(
                new IdeInfo("code-server", "code-server", false),
                new IdeInfo("vscode", "VS Code", true),
                new IdeInfo("intellij", "IntelliJ IDEA", true));
        assertThat(InstalledIdesStore.CODE_SERVER_ID).isEqualTo("code-server");
    }
}
