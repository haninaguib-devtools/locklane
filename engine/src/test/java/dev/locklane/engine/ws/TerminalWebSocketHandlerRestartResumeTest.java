package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.AgentSessionResumeSessionRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #173: after an engine restart the live processes are gone but the resume
 * ids captured by #102/#295/#681 are not, so a client reattaching to a
 * claude/codex/opencode/omp session id gets its conversation back — the launch command
 * resolves to the tool's own resume
 * command, filled from the most recently captured id for that session and tool. A
 * session with nothing captured, an explicit {@code resume} parameter, or a process
 * that is still alive all resolve exactly as they did before.
 */
class TerminalWebSocketHandlerRestartResumeTest {

    private static final String OLDER_ID = "11111111-2222-4333-8444-555555555555";
    private static final String NEWER_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String CODEX_ID = "99999999-8888-4777-8666-555555555554";
    private static final String OPENCODE_ID = "ses_3cf7dd8d4ffeUPfENpVxfFojZ2";
    // #855: every claude argv carries this too, regardless of resume state; captured
    // once so assertions below don't restate its content.
    private static final String CLAUDE_SETTINGS_JSON =
            TerminalWebSocketHandler.resolveLaunchCommand("claude", null)[2];

    @TempDir
    Path dbDir;
    @TempDir
    Path workDir;

    private AgentSessionResumeSessionRepository resumeRepository;
    private SessionRegistry registry;
    private TerminalWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        DataSource dataSource = TestSqliteDatabases.newDataSource(dbDir);
        resumeRepository = new AgentSessionResumeSessionRepository(dataSource);
        registry = new SessionRegistry(new WorktreeSessionRepository(dataSource), resumeRepository);
        handler = new TerminalWebSocketHandler(registry, null);
    }

    @AfterEach
    void tearDown() {
        registry.close("42-worktree");
    }

    @Test
    void aCapturedResumeIdFillsInWhenNoProcessSurvivedTheRestart() {
        resumeRepository.record("42-worktree", "claude", NEWER_ID, Instant.parse("2026-08-27T10:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", "claude", null))
                .containsExactly("claude", "--resume", NEWER_ID, "--settings", CLAUDE_SETTINGS_JSON);
    }

    @Test
    void codexResumesWithItsOwnCommandShape() {
        resumeRepository.record("42-worktree", "codex", CODEX_ID, Instant.parse("2026-08-27T10:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", "codex", null))
                .containsExactly("codex", "resume", CODEX_ID);
    }

    @Test
    void openCodeResumesWithItsOwnCommandShape() {
        resumeRepository.record("42-worktree", "opencode", OPENCODE_ID, Instant.parse("2026-08-27T10:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", "opencode", null))
                .containsExactly("opencode", "--session", OPENCODE_ID);
    }

    @Test
    void ompResumesWithItsOwnCommandShape() {
        resumeRepository.record("42-worktree", "omp", NEWER_ID, Instant.parse("2026-08-27T10:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", "omp", null))
                .containsExactly("omp", "--resume", NEWER_ID);
    }

    @Test
    void theMostRecentlyCapturedIdForTheSameToolWins() {
        resumeRepository.record("42-worktree", "claude", OLDER_ID, Instant.parse("2026-08-27T09:00:00Z"));
        resumeRepository.record("42-worktree", "claude", NEWER_ID, Instant.parse("2026-08-27T10:00:00Z"));
        // A later codex sighting must not hijack a claude relaunch.
        resumeRepository.record("42-worktree", "codex", CODEX_ID, Instant.parse("2026-08-27T11:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", "claude", null))
                .containsExactly("claude", "--resume", NEWER_ID, "--settings", CLAUDE_SETTINGS_JSON);
    }

    @Test
    void nothingCapturedFallsBackToAPlainLaunch() {
        assertThat(handler.resolveLaunchCommand("42-worktree", "claude", null))
                .containsExactly("claude", "--settings", CLAUDE_SETTINGS_JSON);
    }

    @Test
    void anIdCapturedForAnotherSessionDoesNotLeakIn() {
        resumeRepository.record("7-other", "claude", OLDER_ID, Instant.parse("2026-08-27T10:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", "claude", null))
                .containsExactly("claude", "--settings", CLAUDE_SETTINGS_JSON);
    }

    @Test
    void anExplicitResumeParameterStillWins() {
        resumeRepository.record("42-worktree", "claude", NEWER_ID, Instant.parse("2026-08-27T10:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", "claude", OLDER_ID))
                .containsExactly("claude", "--resume", OLDER_ID, "--settings", CLAUDE_SETTINGS_JSON);
    }

    @Test
    void aShellSessionNeverResumes() {
        resumeRepository.record("42-worktree", "claude", NEWER_ID, Instant.parse("2026-08-27T10:00:00Z"));

        assertThat(handler.resolveLaunchCommand("42-worktree", null, null)).isNull();
        assertThat(handler.resolveLaunchCommand("42-worktree", "shell", null)).isNull();
    }

    @Test
    void aLiveProcessSkipsTheLookupSoAReattachIsUntouched() {
        resumeRepository.record("42-worktree", "claude", NEWER_ID, Instant.parse("2026-08-27T10:00:00Z"));
        registry.attach("42-worktree", workDir);

        // The command is ignored by a reattach anyway; resolving it plain proves the
        // repository was never consulted while the original process is still alive.
        assertThat(handler.resolveLaunchCommand("42-worktree", "claude", null))
                .containsExactly("claude", "--settings", CLAUDE_SETTINGS_JSON);
    }
}
