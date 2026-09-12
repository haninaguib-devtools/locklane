package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.AgentSessionResumeSessionRepository;
import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectAgentSessionService;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #537's seeded launch: a brand-new project agent session whose project
 * was created from a template (#536) and has not had its seeded agent session yet starts
 * with the engine-composed first prompt — the t-workflow preface when the checkout
 * carries {@code .t-workflow/}, the plain one otherwise — and is flagged so the
 * attach records it. Everything else resolves exactly as before this task.
 */
class TerminalWebSocketHandlerTemplateSeedTest {
    // Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories and the
    // /console and /consoles REST paths below keep their persisted and on-the-wire shape: compatibility
    // surfaces kept under ADR-112 (#766 renamed only the identifiers).

    // #856: every codex argv carries this too, regardless of seed/resume state -- the
    // fixed path every test-only constructor uses, so this is deterministic.
    private static final String CODEX_NOTIFY_ARG =
            "notify=[\"" + TerminalWebSocketHandler.TEST_CODEX_BELL_NOTIFY_SCRIPT + "\"]";
    // #857: as above, for every omp argv's --hook override.
    private static final String OMP_HOOK_ARG = "--hook=" + TerminalWebSocketHandler.TEST_OMP_BELL_HOOK_EXTENSION;

    @TempDir
    Path dbDir;
    @TempDir
    Path workDir;

    private ProjectRepository projects;
    private SessionRegistry registry;
    private ProjectAgentSessionService agentSessionService;
    private TerminalWebSocketHandler handler;
    // #855: every claude argv carries this too, regardless of seed/resume state;
    // captured once per test so assertions below don't restate its content.
    private String claudeSettingsJson;
    // #904: the tty-capturing wrapper's own script -- byte-for-byte the same for
    // claude and codex -- captured once so wrapped(...) below doesn't restate it.
    private String captureTtyScript;

    @BeforeEach
    void setUp() throws IOException {
        DataSource dataSource = TestSqliteDatabases.newDataSource(dbDir);
        projects = new ProjectRepository(dataSource);
        WorktreeSessionRepository sessions = new WorktreeSessionRepository(dataSource);
        registry = new SessionRegistry(sessions, new AgentSessionResumeSessionRepository(dataSource));
        // Only the project lookup and the template columns matter here; the other
        // collaborators are never reached by templateSeedPrompt/markTemplateSeeded.
        agentSessionService = new ProjectAgentSessionService(projects, new GhAccountRepository(dataSource),
                new TokenCipher(new EncryptionKeyProvider(dbDir.toString())), registry, sessions, null, null, null);
        handler = new TerminalWebSocketHandler(registry, agentSessionService);
        String[] plainestClaudeLaunch = handler.resolveLaunchCommand("claude", null);
        captureTtyScript = plainestClaudeLaunch[2];
        claudeSettingsJson = plainestClaudeLaunch[6];
    }

    /** As {@code TerminalWebSocketHandlerLaunchCommandTest}'s own helper of the same name. */
    private String[] wrapped(String... innerCommand) {
        String[] result = new String[innerCommand.length + 4];
        result[0] = "sh";
        result[1] = "-c";
        result[2] = captureTtyScript;
        result[3] = "sh";
        System.arraycopy(innerCommand, 0, result, 4, innerCommand.length);
        return result;
    }

    @AfterEach
    void tearDown() {
        registry.close("live-agent-session");
    }

    private long templatedProject() {
        return projects.create("templated", "url", dbDir.resolve("templated"), 1L, Instant.now(), "springboot-angular")
                .id();
    }

    @Test
    void eachAgentStartsWithThePlainPrefaceInAPlainCheckout() {
        long id = templatedProject();
        String agentSessionId = id + "-console-a1b2c3d4";

        TerminalWebSocketHandler.Launch claude = handler.resolveLaunch(agentSessionId, "claude", null, "template", workDir);
        TerminalWebSocketHandler.Launch codex = handler.resolveLaunch(agentSessionId, "codex", null, "template", workDir);
        TerminalWebSocketHandler.Launch opencode =
                handler.resolveLaunch(agentSessionId, "opencode", null, "template", workDir);
        TerminalWebSocketHandler.Launch omp = handler.resolveLaunch(agentSessionId, "omp", null, "template", workDir);

        assertThat(claude.seeded()).isTrue();
        assertThat(claude.command())
                .containsExactly(wrapped("claude", ProjectAgentSessionService.PLAIN_SEED_PROMPT, "--settings", claudeSettingsJson));
        assertThat(codex.command())
                .containsExactly(wrapped("codex", ProjectAgentSessionService.PLAIN_SEED_PROMPT, "-c", CODEX_NOTIFY_ARG));
        assertThat(opencode.command())
                .containsExactly("opencode", "--prompt", ProjectAgentSessionService.PLAIN_SEED_PROMPT);
        assertThat(omp.seeded()).isTrue();
        assertThat(omp.command())
                .containsExactly("omp", ProjectAgentSessionService.PLAIN_SEED_PROMPT, OMP_HOOK_ARG);
        assertThat(ProjectAgentSessionService.PLAIN_SEED_PROMPT).contains("PROJECT_TEMPLATE.md").contains("push")
                .doesNotContain("/t-open");
    }

    @Test
    void aTWorkflowCheckoutGetsTheTWorkflowPreface() throws IOException {
        long id = templatedProject();
        Files.createDirectories(workDir.resolve(".t-workflow"));

        TerminalWebSocketHandler.Launch launch =
                handler.resolveLaunch(id + "-console-a1b2c3d4", "claude", null, "template", workDir);

        assertThat(launch.seeded()).isTrue();
        assertThat(launch.command()).containsExactly(wrapped("claude", ProjectAgentSessionService.T_WORKFLOW_SEED_PROMPT,
                "--settings", claudeSettingsJson));
        assertThat(ProjectAgentSessionService.T_WORKFLOW_SEED_PROMPT).contains("PROJECT_TEMPLATE.md").contains("/t-open")
                .contains("/t-drive");
    }

    @Test
    void seedIsIgnoredForAShellForANonAgentSessionAndForAProjectWithNoTemplate() {
        long templated = templatedProject();
        long plain = projects.create("plain", "url", dbDir.resolve("plain"), 1L, Instant.now()).id();

        assertThat(handler.resolveLaunch(templated + "-console-a1b2c3d4", "shell", null, "template", workDir))
                .satisfies(l -> {
                    assertThat(l.seeded()).isFalse();
                    assertThat(l.command()).isNull();
                });
        assertThat(handler.resolveLaunch(templated + "-42-main-a1b2c3d4", "claude", null, "template", workDir))
                .satisfies(l -> {
                    assertThat(l.seeded()).isFalse();
                    assertThat(l.command()).containsExactly(wrapped("claude", "--settings", claudeSettingsJson));
                });
        assertThat(handler.resolveLaunch(plain + "-console-a1b2c3d4", "claude", null, "template", workDir))
                .satisfies(l -> {
                    assertThat(l.seeded()).isFalse();
                    assertThat(l.command()).containsExactly(wrapped("claude", "--settings", claudeSettingsJson));
                });
        assertThat(handler.resolveLaunch(templated + "-console-a1b2c3d4", "claude", null, null, workDir).seeded())
                .isFalse();
        assertThat(handler.resolveLaunch(templated + "-console-a1b2c3d4", "claude", null, "anything-else", workDir)
                .seeded()).isFalse();
    }

    @Test
    void anExplicitResumeWinsOverTheSeed() {
        long id = templatedProject();
        String resumeId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

        TerminalWebSocketHandler.Launch launch =
                handler.resolveLaunch(id + "-console-a1b2c3d4", "claude", resumeId, "template", workDir);

        assertThat(launch.seeded()).isFalse();
        assertThat(launch.command()).containsExactly(wrapped("claude", "--resume", resumeId, "--settings", claudeSettingsJson));
    }

    @Test
    void aReattachToALiveProcessIsNeverSeeded() {
        long id = templatedProject();
        registry.attach("live-agent-session", workDir, new String[] {"sh"});
        // The live session's id is not this project's agent session id -- what matters is
        // that a live process short-circuits the seed before any project lookup.
        assertThat(handler.resolveLaunch("live-agent-session", "claude", null, "template", workDir).seeded()).isFalse();
        // And a genuine agent session id with a live process behind it: attach one under that id too.
        String agentSessionId = id + "-console-b2c3d4e5";
        registry.attach(agentSessionId, workDir, new String[] {"sh"});
        try {
            assertThat(handler.resolveLaunch(agentSessionId, "claude", null, "template", workDir).seeded()).isFalse();
        } finally {
            registry.close(agentSessionId);
        }
    }

    @Test
    void onceRecordedAsSeededTheNextSeededAttachLaunchesWithoutAPrompt() {
        long id = templatedProject();
        String agentSessionId = id + "-console-a1b2c3d4";
        assertThat(handler.resolveLaunch(agentSessionId, "codex", null, "template", workDir).seeded()).isTrue();

        assertThat(agentSessionService.markTemplateSeeded(agentSessionId, Instant.parse("2026-09-01T12:00:00Z"))).isTrue();

        ProjectRecord after = projects.findById(id).orElseThrow();
        assertThat(after.templateSeededAt()).isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
        TerminalWebSocketHandler.Launch second =
                handler.resolveLaunch(id + "-console-ffffffff", "codex", null, "template", workDir);
        assertThat(second.seeded()).isFalse();
        assertThat(second.command()).containsExactly(wrapped("codex", "-c", CODEX_NOTIFY_ARG));
        // A second mark is refused rather than moving the timestamp.
        assertThat(agentSessionService.markTemplateSeeded(agentSessionId, Instant.parse("2026-09-02T12:00:00Z"))).isFalse();
        assertThat(projects.findById(id).orElseThrow().templateSeededAt())
                .isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
    }

    @Test
    void markingAProjectWithNoTemplateOrANonAgentSessionIdWritesNothing() {
        long plain = projects.create("plain", "url", dbDir.resolve("plain"), 1L, Instant.now()).id();
        long templated = templatedProject();

        assertThat(agentSessionService.markTemplateSeeded(plain + "-console-a1b2c3d4", Instant.now())).isFalse();
        assertThat(agentSessionService.markTemplateSeeded(templated + "-7-main-a1b2c3d4", Instant.now())).isFalse();
        assertThat(agentSessionService.markTemplateSeeded("garbage", Instant.now())).isFalse();

        assertThat(projects.findById(plain).orElseThrow().templateSeededAt()).isNull();
        assertThat(projects.findById(templated).orElseThrow().templateSeededAt()).isNull();
    }
}
