package dev.locklane.engine.ws;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launch command a brand-new session gets from the {@code cmd}/{@code resume}
 * query parameters (#103): resuming a past conversation composes the tool's own
 * resume command server-side; anything unexpected degrades to the plain command
 * (or the default shell), never to a command containing an unvetted argument.
 */
class TerminalWebSocketHandlerLaunchCommandTest {

    private static final String UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OPENCODE_ID = "ses_3cf7dd8d4ffeUPfENpVxfFojZ2";

    @Test
    void absentBlankOrShellCmdDefersToTheDefaultShell() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand(null, null)).isNull();
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand(" ", null)).isNull();
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("shell", UUID)).isNull();
    }

    @Test
    void aPlainCmdLaunchesAsItself() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", null)).containsExactly("claude");
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("codex", null)).containsExactly("codex");
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("opencode", null)).containsExactly("opencode");
    }

    @Test
    void aResumeIdComposesTheToolsOwnResumeCommand() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", UUID))
                .containsExactly("claude", "--resume", UUID);
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("codex", UUID))
                .containsExactly("codex", "resume", UUID);
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("opencode", OPENCODE_ID))
                .containsExactly("opencode", "--session", OPENCODE_ID);
    }

    @Test
    void aResumeIdNotShapedLikeACapturedIdIsIgnored() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", "--dangerously-skip-permissions"))
                .containsExactly("claude");
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", "not-a-uuid"))
                .containsExactly("claude");
    }

    // #537: the seeded first prompt rides as one argv element in each agent's own
    // "start with this prompt" shape; anything that is not one of the three agents
    // gets no seeded command at all.

    @Test
    void aSeededLaunchUsesEachAgentsOwnInitialPromptShape() {
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("claude", "do it"))
                .containsExactly("claude", "do it");
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("codex", "do it"))
                .containsExactly("codex", "do it");
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("opencode", "do it"))
                .containsExactly("opencode", "--prompt", "do it");
    }

    @Test
    void aSeededLaunchForAnythingElseIsNull() {
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("shell", "do it")).isNull();
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("vim", "do it")).isNull();
        assertThat(TerminalWebSocketHandler.seededLaunchCommand(null, "do it")).isNull();
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("claude", null)).isNull();
    }

    @Test
    void aResumeIdWithACmdThatIsNeitherToolIsIgnored() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("vim", UUID)).containsExactly("vim");
    }
}
