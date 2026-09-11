package dev.locklane.engine.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launch command a brand-new session gets from the {@code cmd}/{@code resume}
 * query parameters (#103): resuming a past conversation composes the tool's own
 * resume command server-side; anything unexpected degrades to the plain command
 * (or the default shell), never to a command containing an unvetted argument.
 *
 * <p>Also covers #855: every {@code claude} launch this handler composes -- plain,
 * resumed, or seeded with a first prompt -- carries {@code --settings} plus a JSON
 * argv element wiring the bell hooks ADR-113 records; every other tool's argv is
 * untouched.
 */
class TerminalWebSocketHandlerLaunchCommandTest {

    private static final String UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OPENCODE_ID = "ses_3cf7dd8d4ffeUPfENpVxfFojZ2";

    // The bell-hooks settings JSON is a private implementation detail of
    // TerminalWebSocketHandler; captured once here (from the plainest claude launch)
    // so every other assertion below can check for the very same argv element
    // without depending on its internal shape.
    private static final String CLAUDE_SETTINGS_JSON =
            TerminalWebSocketHandler.resolveLaunchCommand("claude", null)[2];

    @Test
    void absentBlankOrShellCmdDefersToTheDefaultShell() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand(null, null)).isNull();
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand(" ", null)).isNull();
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("shell", UUID)).isNull();
    }

    @Test
    void aPlainCmdLaunchesAsItself() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", null))
                .containsExactly("claude", "--settings", CLAUDE_SETTINGS_JSON);
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("codex", null)).containsExactly("codex");
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("opencode", null)).containsExactly("opencode");
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("omp", null)).containsExactly("omp");
    }

    @Test
    void aResumeIdComposesTheToolsOwnResumeCommand() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", UUID))
                .containsExactly("claude", "--resume", UUID, "--settings", CLAUDE_SETTINGS_JSON);
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("codex", UUID))
                .containsExactly("codex", "resume", UUID);
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("opencode", OPENCODE_ID))
                .containsExactly("opencode", "--session", OPENCODE_ID);
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("omp", UUID))
                .containsExactly("omp", "--resume", UUID);
    }

    @Test
    void aResumeIdNotShapedLikeACapturedIdIsIgnored() {
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", "--dangerously-skip-permissions"))
                .containsExactly("claude", "--settings", CLAUDE_SETTINGS_JSON);
        assertThat(TerminalWebSocketHandler.resolveLaunchCommand("claude", "not-a-uuid"))
                .containsExactly("claude", "--settings", CLAUDE_SETTINGS_JSON);
    }

    // #537: the seeded first prompt rides as one argv element in each agent's own
    // "start with this prompt" shape; anything that is not one of the four agents
    // gets no seeded command at all.

    @Test
    void aSeededLaunchUsesEachAgentsOwnInitialPromptShape() {
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("claude", "do it"))
                .containsExactly("claude", "do it", "--settings", CLAUDE_SETTINGS_JSON);
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("codex", "do it"))
                .containsExactly("codex", "do it");
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("opencode", "do it"))
                .containsExactly("opencode", "--prompt", "do it");
        assertThat(TerminalWebSocketHandler.seededLaunchCommand("omp", "do it"))
                .containsExactly("omp", "do it");
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

    @Test
    void theClaudeSettingsJsonWiresTheBellToStopAskUserQuestionAndPermissionPrompt() throws IOException {
        // #855, ADR-113: one hook command answers all three points a turn can stop
        // at -- parsed here (not string-matched) since Claude Code's settings schema
        // is what actually has to accept this, not any particular JSON formatting.
        JsonNode hooks = new ObjectMapper().readTree(CLAUDE_SETTINGS_JSON).path("hooks");
        String bellCommand = "printf '\\a' > /dev/tty";

        assertThat(hookCommand(hooks, "Stop", null)).isEqualTo(bellCommand);
        assertThat(hookCommand(hooks, "PreToolUse", "AskUserQuestion")).isEqualTo(bellCommand);
        assertThat(hookCommand(hooks, "Notification", "permission_prompt")).isEqualTo(bellCommand);
    }

    /** The {@code command} of the first hook in {@code event}'s group matching {@code matcher} (null = no matcher key expected). */
    private static String hookCommand(JsonNode hooks, String event, String matcher) {
        for (JsonNode group : hooks.path(event)) {
            String groupMatcher = group.hasNonNull("matcher") ? group.path("matcher").asText() : null;
            if (java.util.Objects.equals(matcher, groupMatcher)) {
                return group.path("hooks").get(0).path("command").asText();
            }
        }
        throw new AssertionError("no " + event + " hook group matching " + matcher + " in " + hooks);
    }
}
