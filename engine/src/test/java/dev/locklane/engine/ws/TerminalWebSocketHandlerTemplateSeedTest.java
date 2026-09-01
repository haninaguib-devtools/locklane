package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ConsoleResumeSessionRepository;
import dev.locklane.engine.persistence.ProjectConsoleService;
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
 * #537's seeded launch: a brand-new agent session of a project console whose project
 * was created from a template (#536) and has not had its seeded console yet starts
 * with the engine-composed first prompt — the t-workflow preface when the checkout
 * carries {@code .t-workflow/}, the plain one otherwise — and is flagged so the
 * attach records it. Everything else resolves exactly as before this task.
 */
class TerminalWebSocketHandlerTemplateSeedTest {

    @TempDir
    Path dbDir;
    @TempDir
    Path workDir;

    private ProjectRepository projects;
    private SessionRegistry registry;
    private ProjectConsoleService consoleService;
    private TerminalWebSocketHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        DataSource dataSource = TestSqliteDatabases.newDataSource(dbDir);
        projects = new ProjectRepository(dataSource);
        WorktreeSessionRepository sessions = new WorktreeSessionRepository(dataSource);
        registry = new SessionRegistry(sessions, new ConsoleResumeSessionRepository(dataSource));
        // Only the project lookup and the template columns matter here; the other
        // collaborators are never reached by templateSeedPrompt/markTemplateSeeded.
        consoleService = new ProjectConsoleService(projects, new TokenCipher(new EncryptionKeyProvider(dbDir.toString())),
                registry, sessions, null, null, null);
        handler = new TerminalWebSocketHandler(registry, consoleService);
    }

    @AfterEach
    void tearDown() {
        registry.close("live-console");
    }

    private long templatedProject() {
        return projects.create("templated", "url", dbDir.resolve("templated"), 1L, Instant.now(), "springboot-angular")
                .id();
    }

    @Test
    void eachAgentStartsWithThePlainPrefaceInAPlainCheckout() {
        long id = templatedProject();
        String consoleId = id + "-console-a1b2c3d4";

        TerminalWebSocketHandler.Launch claude = handler.resolveLaunch(consoleId, "claude", null, "template", workDir);
        TerminalWebSocketHandler.Launch codex = handler.resolveLaunch(consoleId, "codex", null, "template", workDir);
        TerminalWebSocketHandler.Launch opencode =
                handler.resolveLaunch(consoleId, "opencode", null, "template", workDir);

        assertThat(claude.seeded()).isTrue();
        assertThat(claude.command()).containsExactly("claude", ProjectConsoleService.PLAIN_SEED_PROMPT);
        assertThat(codex.command()).containsExactly("codex", ProjectConsoleService.PLAIN_SEED_PROMPT);
        assertThat(opencode.command())
                .containsExactly("opencode", "--prompt", ProjectConsoleService.PLAIN_SEED_PROMPT);
        assertThat(ProjectConsoleService.PLAIN_SEED_PROMPT).contains("PROJECT_TEMPLATE.md").contains("push")
                .doesNotContain("/t-open");
    }

    @Test
    void aTWorkflowCheckoutGetsTheTWorkflowPreface() throws IOException {
        long id = templatedProject();
        Files.createDirectories(workDir.resolve(".t-workflow"));

        TerminalWebSocketHandler.Launch launch =
                handler.resolveLaunch(id + "-console-a1b2c3d4", "claude", null, "template", workDir);

        assertThat(launch.seeded()).isTrue();
        assertThat(launch.command()).containsExactly("claude", ProjectConsoleService.T_WORKFLOW_SEED_PROMPT);
        assertThat(ProjectConsoleService.T_WORKFLOW_SEED_PROMPT).contains("PROJECT_TEMPLATE.md").contains("/t-open")
                .contains("/t-drive");
    }

    @Test
    void seedIsIgnoredForAShellForANonConsoleSessionAndForAProjectWithNoTemplate() {
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
                    assertThat(l.command()).containsExactly("claude");
                });
        assertThat(handler.resolveLaunch(plain + "-console-a1b2c3d4", "claude", null, "template", workDir))
                .satisfies(l -> {
                    assertThat(l.seeded()).isFalse();
                    assertThat(l.command()).containsExactly("claude");
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
        assertThat(launch.command()).containsExactly("claude", "--resume", resumeId);
    }

    @Test
    void aReattachToALiveProcessIsNeverSeeded() {
        long id = templatedProject();
        registry.attach("live-console", workDir, new String[] {"sh"});
        // The live session's id is not this project's console id -- what matters is
        // that a live process short-circuits the seed before any project lookup.
        assertThat(handler.resolveLaunch("live-console", "claude", null, "template", workDir).seeded()).isFalse();
        // And a genuine console id with a live process behind it: attach one under that id too.
        String consoleId = id + "-console-b2c3d4e5";
        registry.attach(consoleId, workDir, new String[] {"sh"});
        try {
            assertThat(handler.resolveLaunch(consoleId, "claude", null, "template", workDir).seeded()).isFalse();
        } finally {
            registry.close(consoleId);
        }
    }

    @Test
    void onceRecordedAsSeededTheNextSeededAttachLaunchesWithoutAPrompt() {
        long id = templatedProject();
        String consoleId = id + "-console-a1b2c3d4";
        assertThat(handler.resolveLaunch(consoleId, "codex", null, "template", workDir).seeded()).isTrue();

        assertThat(consoleService.markTemplateSeeded(consoleId, Instant.parse("2026-09-01T12:00:00Z"))).isTrue();

        ProjectRecord after = projects.findById(id).orElseThrow();
        assertThat(after.templateSeededAt()).isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
        TerminalWebSocketHandler.Launch second =
                handler.resolveLaunch(id + "-console-ffffffff", "codex", null, "template", workDir);
        assertThat(second.seeded()).isFalse();
        assertThat(second.command()).containsExactly("codex");
        // A second mark is refused rather than moving the timestamp.
        assertThat(consoleService.markTemplateSeeded(consoleId, Instant.parse("2026-09-02T12:00:00Z"))).isFalse();
        assertThat(projects.findById(id).orElseThrow().templateSeededAt())
                .isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
    }

    @Test
    void markingAProjectWithNoTemplateOrANonConsoleIdWritesNothing() {
        long plain = projects.create("plain", "url", dbDir.resolve("plain"), 1L, Instant.now()).id();
        long templated = templatedProject();

        assertThat(consoleService.markTemplateSeeded(plain + "-console-a1b2c3d4", Instant.now())).isFalse();
        assertThat(consoleService.markTemplateSeeded(templated + "-7-main-a1b2c3d4", Instant.now())).isFalse();
        assertThat(consoleService.markTemplateSeeded("garbage", Instant.now())).isFalse();

        assertThat(projects.findById(plain).orElseThrow().templateSeededAt()).isNull();
        assertThat(projects.findById(templated).orElseThrow().templateSeededAt()).isNull();
    }
}
