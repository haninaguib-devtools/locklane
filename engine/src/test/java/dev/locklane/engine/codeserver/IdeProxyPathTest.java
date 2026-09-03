package dev.locklane.engine.codeserver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdeProxyPathTest {

    @Test
    void parsesProjectConsoleAndTheRawRemainder() {
        var path = IdeProxyPath.parse("/api/projects/12/consoles/12-174-rename-toggle/ide/static/a%20b.js");

        assertThat(path).contains(new IdeProxyPath(12, "12-174-rename-toggle", "/static/a%20b.js"));
    }

    @Test
    void theBareIdeSegmentHasNoRemainderAndTheSlashedOneHasTheRootRemainder() {
        assertThat(IdeProxyPath.parse("/api/projects/1/consoles/1-console-0a1b2c3d/ide"))
                .contains(new IdeProxyPath(1, "1-console-0a1b2c3d", null));
        assertThat(IdeProxyPath.parse("/api/projects/1/consoles/1-console-0a1b2c3d/ide/"))
                .contains(new IdeProxyPath(1, "1-console-0a1b2c3d", "/"));
    }

    @Test
    void rejectsEverythingOutsideTheFamily() {
        assertThat(IdeProxyPath.parse(null)).isEmpty();
        assertThat(IdeProxyPath.parse("/api/projects/1/consoles/1-x/open-ide")).isEmpty();
        assertThat(IdeProxyPath.parse("/api/projects/x/consoles/1-x/ide/")).isEmpty();
        assertThat(IdeProxyPath.parse("/api/projects/1/consoles//ide/")).isEmpty();
        assertThat(IdeProxyPath.parse("/api/projects/1/consoles/1-x/ideas/")).isEmpty();
    }
}
