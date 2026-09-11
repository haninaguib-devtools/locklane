package dev.locklane.engine.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launch command a brand-new session gets from the {@code cmd}/{@code resume}
 * query parameters (#103): resuming a past conversation composes the tool's own
 * resume command server-side; anything unexpected degrades to the plain command
 * (or the default shell), never to a command containing an unvetted argument.
 *
 * <p>Also covers #855: every {@code claude} launch this handler composes -- plain,
 * resumed, or seeded with a first prompt -- carries {@code --settings} plus a JSON
 * argv element wiring the bell hooks ADR-113 records.
 *
 * <p>Also covers #856: every {@code codex} launch carries {@code -c notify=[...]}
 * naming the bell script {@link CodexBellHookScript} installs. Every other tool's
 * argv is untouched by either.
 */
class TerminalWebSocketHandlerLaunchCommandTest {

    private static final String UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OPENCODE_ID = "ses_3cf7dd8d4ffeUPfENpVxfFojZ2";

    // Neither field is dereferenced by resolveLaunchCommand/seededLaunchCommand.
    private TerminalWebSocketHandler handler;

    // The bell-hooks settings JSON is a private implementation detail of
    // TerminalWebSocketHandler; captured once here (from the plainest claude launch)
    // so every other assertion below can check for the very same argv element
    // without depending on its internal shape.
    private String claudeSettingsJson;

    // The codex notify override, likewise captured from the plainest codex launch --
    // TerminalWebSocketHandler.TEST_CODEX_BELL_NOTIFY_SCRIPT is the fixed path every
    // test-only constructor uses, so this is deterministic across runs.
    private static final String CODEX_NOTIFY_ARG =
            "notify=[\"" + TerminalWebSocketHandler.TEST_CODEX_BELL_NOTIFY_SCRIPT + "\"]";

    // As above, for omp's --hook override (#857).
    private static final String OMP_HOOK_ARG = "--hook=" + TerminalWebSocketHandler.TEST_OMP_BELL_HOOK_EXTENSION;

    @BeforeEach
    void setUp() {
        handler = new TerminalWebSocketHandler(null, null);
        claudeSettingsJson = handler.resolveLaunchCommand("claude", null)[2];
    }

    @Test
    void absentBlankOrShellCmdDefersToTheDefaultShell() {
        assertThat(handler.resolveLaunchCommand(null, null)).isNull();
        assertThat(handler.resolveLaunchCommand(" ", null)).isNull();
        assertThat(handler.resolveLaunchCommand("shell", UUID)).isNull();
    }

    @Test
    void aPlainCmdLaunchesAsItself() {
        assertThat(handler.resolveLaunchCommand("claude", null))
                .containsExactly("claude", "--settings", claudeSettingsJson);
        assertThat(handler.resolveLaunchCommand("codex", null))
                .containsExactly("codex", "-c", CODEX_NOTIFY_ARG);
        assertThat(handler.resolveLaunchCommand("opencode", null)).containsExactly("opencode");
        assertThat(handler.resolveLaunchCommand("omp", null)).containsExactly("omp", OMP_HOOK_ARG);
    }

    @Test
    void aResumeIdComposesTheToolsOwnResumeCommand() {
        assertThat(handler.resolveLaunchCommand("claude", UUID))
                .containsExactly("claude", "--resume", UUID, "--settings", claudeSettingsJson);
        assertThat(handler.resolveLaunchCommand("codex", UUID))
                .containsExactly("codex", "resume", UUID, "-c", CODEX_NOTIFY_ARG);
        assertThat(handler.resolveLaunchCommand("opencode", OPENCODE_ID))
                .containsExactly("opencode", "--session", OPENCODE_ID);
        assertThat(handler.resolveLaunchCommand("omp", UUID))
                .containsExactly("omp", "--resume", UUID, OMP_HOOK_ARG);
    }

    @Test
    void aResumeIdNotShapedLikeACapturedIdIsIgnored() {
        assertThat(handler.resolveLaunchCommand("claude", "--dangerously-skip-permissions"))
                .containsExactly("claude", "--settings", claudeSettingsJson);
        assertThat(handler.resolveLaunchCommand("claude", "not-a-uuid"))
                .containsExactly("claude", "--settings", claudeSettingsJson);
        assertThat(handler.resolveLaunchCommand("codex", "not-a-uuid"))
                .containsExactly("codex", "-c", CODEX_NOTIFY_ARG);
    }

    // #537: the seeded first prompt rides as one argv element in each agent's own
    // "start with this prompt" shape; anything that is not one of the four agents
    // gets no seeded command at all.

    @Test
    void aSeededLaunchUsesEachAgentsOwnInitialPromptShape() {
        assertThat(handler.seededLaunchCommand("claude", "do it"))
                .containsExactly("claude", "do it", "--settings", claudeSettingsJson);
        assertThat(handler.seededLaunchCommand("codex", "do it"))
                .containsExactly("codex", "do it", "-c", CODEX_NOTIFY_ARG);
        assertThat(handler.seededLaunchCommand("opencode", "do it"))
                .containsExactly("opencode", "--prompt", "do it");
        assertThat(handler.seededLaunchCommand("omp", "do it"))
                .containsExactly("omp", "do it", OMP_HOOK_ARG);
    }

    @Test
    void aSeededLaunchForAnythingElseIsNull() {
        assertThat(handler.seededLaunchCommand("shell", "do it")).isNull();
        assertThat(handler.seededLaunchCommand("vim", "do it")).isNull();
        assertThat(handler.seededLaunchCommand(null, "do it")).isNull();
        assertThat(handler.seededLaunchCommand("claude", null)).isNull();
    }

    @Test
    void aResumeIdWithACmdThatIsNeitherToolIsIgnored() {
        assertThat(handler.resolveLaunchCommand("vim", UUID)).containsExactly("vim");
    }

    @Test
    void theClaudeSettingsJsonWiresTheBellToStopAskUserQuestionAndPermissionPrompt() throws IOException {
        // #855, ADR-113: one hook command answers all three points a turn can stop
        // at -- parsed here (not string-matched) since Claude Code's settings schema
        // is what actually has to accept this, not any particular JSON formatting.
        JsonNode hooks = new ObjectMapper().readTree(claudeSettingsJson).path("hooks");
        String bellCommand = "printf '\\a' > /dev/tty";

        assertThat(hookCommand(hooks, "Stop", null)).isEqualTo(bellCommand);
        assertThat(hookCommand(hooks, "PreToolUse", "AskUserQuestion")).isEqualTo(bellCommand);
        assertThat(hookCommand(hooks, "Notification", "permission_prompt")).isEqualTo(bellCommand);
    }

    /** The {@code command} of the first hook in {@code event}'s group matching {@code matcher} (null = no matcher key expected). */
    private static String hookCommand(JsonNode hooks, String event, String matcher) {
        for (JsonNode group : hooks.path(event)) {
            String groupMatcher = group.hasNonNull("matcher") ? group.path("matcher").asText() : null;
            if (Objects.equals(matcher, groupMatcher)) {
                return group.path("hooks").get(0).path("command").asText();
            }
        }
        throw new AssertionError("no " + event + " hook group matching " + matcher + " in " + hooks);
    }
}
