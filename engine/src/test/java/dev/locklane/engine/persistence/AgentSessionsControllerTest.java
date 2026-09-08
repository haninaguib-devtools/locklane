package dev.locklane.engine.persistence;

import dev.locklane.engine.codeserver.CodeServerService;
import dev.locklane.engine.ide.DesktopIdeLauncher;
import dev.locklane.engine.ide.IdeInfo;
import dev.locklane.engine.ide.InstalledIde;
import dev.locklane.engine.ide.InstalledIdesStore;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionsControllerTest {
    // Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories and the
    // /console and /consoles REST paths below keep their persisted and on-the-wire shape: compatibility
    // surfaces kept under ADR-112 (#766 renamed only the identifiers).

    private static final Principal ALICE = () -> "alice";

    /**
     * Stub listeners {@link #codeServerService} binds, kept alive for the class's
     * whole run (#776): an unreferenced {@link ServerSocket} is eligible for the JVM
     * to reclaim -- and close -- before {@code CodeServerService.start}'s own wait for
     * a connection gets to it.
     */
    private static final List<ServerSocket> CODE_SERVER_STUBS = new CopyOnWriteArrayList<>();

    @Test
    void returnsEveryVisibleAgentSessionAcrossIssuesInTheProjectRegardlessOfWhoAttached(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        // #242: visibility is derived from the project alice owns, not from who
        // attached -- bob attaching to a session in project 1 doesn't move it out
        // of alice's view.
        repository.recordAttach("1-175-bobs-session", dbDir.resolve("wt2"), now, "bob");
        repository.recordAttach("2-174-other-project", dbDir.resolve("wt3"), now, "alice");
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.agentSessions(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-175-bobs-session");
    }

    @Test
    void includesTheProjectsOwnAgentSessionsAlongsideItsIssues(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "alice");
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.agentSessions(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-console-0a1b2c3d");
    }

    @Test
    void returnsAnEmptyListWithNoOpenAgentSessions(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.agentSessions(1, ALICE)).isEmpty();
    }

    @Test
    void revealFailsFastForAAgentSessionIdOutsideTheCallersProject(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("2-174-not-alices", dbDir.resolve("wt1"), Instant.now(), "alice");
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        // Never reaches FileManagerLauncher at all -- the ownership check refuses
        // before any lookup of a working directory, exactly like an unknown id would.
        assertThat(controller.reveal(1, "2-174-not-alices", loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // And before the loopback rule (#784): a remote caller probing another
        // project's agent session ids gets the same 404 it always did, never a 403.
        assertThat(controller.reveal(1, "2-174-not-alices", remote("192.168.1.20"), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void revealFromALoopbackPeerLaunchesTheFileManager(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        List<String[]> launched = new CopyOnWriteArrayList<>();
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository),
                launcher(repository, launched), codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.reveal(1, "1-174-rename-toggle", loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Unchanged for a localhost browser (#784): the same file-manager command
        // #441 always ran, on the agent session's own worktree.
        assertThat(launched).hasSize(1);
        assertThat(launched.get(0)).isEqualTo(FileManagerLauncher.revealCommand(System.getProperty("os.name", ""), worktree));
    }

    @Test
    void revealFromANonLoopbackPeerIsForbiddenAndLaunchesNothing(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<String[]> launched = new CopyOnWriteArrayList<>();
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository),
                launcher(repository, launched), codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        // The owner of the project, but from another machine (#784): the file manager
        // would open on the engine host's desktop, not the caller's.
        assertThat(controller.reveal(1, "1-174-rename-toggle", remote("192.168.1.20"), ALICE).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(launched).isEmpty();
    }

    @Test
    void revealFromALoopbackPeerBehindAProxyIsForbiddenAndLaunchesNothing(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        List<String[]> launched = new CopyOnWriteArrayList<>();
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository),
                launcher(repository, launched), codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));
        MockHttpServletRequest relayed = loopback();
        relayed.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(controller.reveal(1, "1-174-rename-toggle", relayed, ALICE).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(launched).isEmpty();
    }

    @Test
    void openIdeReturnsTheProxiedIdePathForAVisibleAgentSession(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        ResponseEntity<AgentSessionsController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle", null, loopback(), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The engine's own proxied path (#655), relative and slash-terminated -- never
        // the loopback address the process itself listens on -- carrying the agent session's
        // worktree as a `folder` query parameter (#776) so a bookmark or a bare refresh
        // still opens this agent session's own workspace.
        assertThat(response.getBody().url()).isEqualTo(
                "/api/projects/1/consoles/1-174-rename-toggle/ide/?folder="
                        + java.net.URLEncoder.encode(worktree.toString(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void openIdeFailsFastForAAgentSessionIdOutsideTheCallersProject(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("2-174-not-alices", dbDir.resolve("wt1"), Instant.now(), "alice");
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.openIde(1, "2-174-not-alices", null, loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The visibility rule is the same for every id (#781): a desktop id on an agent session
        // outside the caller's project is a 404 too, never a 403 or a launch.
        assertThat(controller.openIde(1, "2-174-not-alices", new AgentSessionsController.OpenIdeRequest("vscode"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openIdeWithCodeServerNamedExplicitlyBehavesExactlyAsWithNoBody(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        ResponseEntity<AgentSessionsController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new AgentSessionsController.OpenIdeRequest("code-server"), remote("192.168.1.20"), ALICE);

        // Reached from a remote browser, too: the loopback gate is for desktop IDEs only.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().url()).startsWith("/api/projects/1/consoles/1-174-rename-toggle/ide/?folder=");
        verify(desktop, never()).launch(anyString(), any());
    }

    @Test
    void openIdeWithADesktopIdFromALoopbackPeerLaunchesItAndReturnsNoUrl(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        InstalledIdesStore store = installedIdesStore();
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        when(desktop.launch(anyString(), any())).thenReturn(true);
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), store, desktop);

        ResponseEntity<AgentSessionsController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new AgentSessionsController.OpenIdeRequest("intellij"), loopback(), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().url()).isNull();
        verify(desktop).launch("1-174-rename-toggle", store.find("intellij").orElseThrow());
    }

    @Test
    void openIdeWithADesktopIdFromANonLoopbackPeerIsForbiddenAndLaunchesNothing(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        ResponseEntity<AgentSessionsController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new AgentSessionsController.OpenIdeRequest("intellij"), remote("192.168.1.20"), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(desktop, never()).launch(anyString(), any());
    }

    @Test
    void openIdeWithADesktopIdFromALoopbackPeerBehindAProxyIsForbiddenAndLaunchesNothing(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);
        MockHttpServletRequest relayed = loopback();
        relayed.addHeader("X-Forwarded-For", "203.0.113.7");

        ResponseEntity<AgentSessionsController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new AgentSessionsController.OpenIdeRequest("intellij"), relayed, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(desktop, never()).launch(anyString(), any());
    }

    @Test
    void openIdeWithAnUnknownOrNotInstalledIdIsABadRequestAndLaunchesNothing(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        // "vscode" is a known id, but installedIdesStore() below found only IntelliJ on this host.
        assertThat(controller.openIde(1, "1-174-rename-toggle", new AgentSessionsController.OpenIdeRequest("vscode"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.openIde(1, "1-174-rename-toggle", new AgentSessionsController.OpenIdeRequest("emacs"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(desktop, never()).launch(anyString(), any());
    }

    @Test
    void openIdeWithADesktopIdIsNotFoundForAAgentSessionWithNoKnownWorkingDirectory(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        when(desktop.launch(anyString(), any())).thenReturn(false);
        AgentSessionsController controller = new AgentSessionsController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        assertThat(controller.openIde(1, "1-174-rename-toggle", new AgentSessionsController.OpenIdeRequest("intellij"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A request straight from a browser on the engine's own machine (#781). */
    private static MockHttpServletRequest loopback() {
        return remote("127.0.0.1");
    }

    /**
     * A request whose peer is {@code peerAddress}. The path is nominal: neither
     * {@link AgentSessionsController#openIde} nor {@link AgentSessionsController#reveal} reads it,
     * only the peer address and the forwarding headers.
     */
    private static MockHttpServletRequest remote(String peerAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects/1/consoles/1-174-rename-toggle/open-ide");
        request.setRemoteAddr(peerAddress);
        return request;
    }

    /**
     * A store on which the boot probe found IntelliJ IDEA (on {@code PATH}) but not VS
     * Code: {@code find("intellij")} answers, every other id is empty, as Mockito
     * answers an unstubbed {@code Optional}-returning method.
     */
    private static InstalledIdesStore installedIdesStore() {
        InstalledIdesStore store = mock(InstalledIdesStore.class);
        when(store.find("intellij")).thenReturn(java.util.Optional.of(
                new InstalledIde(new IdeInfo("intellij", "IntelliJ IDEA", true), null)));
        return store;
    }

    /** A real project row owned by {@code ownerUsername}'s (freshly created) account. */
    private static void createProject(Path dbDir, String ownerUsername) {
        UserRecord owner = TestSqliteDatabases.newUserRepository(dbDir).create(ownerUsername, "bcrypt-hash", Instant.now());
        TestSqliteDatabases.newProjectRepository(dbDir).createReady("proj-" + ownerUsername, "url",
                dbDir.resolve("work-" + ownerUsername), "main", owner.id(), Instant.now());
    }

    private static IssueWorktreeService worktreeService(Path dbDir, WorktreeSessionRepository repository) {
        WorktreeSessionAuthorization authorization = new WorktreeSessionAuthorization(
                TestSqliteDatabases.newProjectRepository(dbDir), TestSqliteDatabases.newUserRepository(dbDir));
        return new IssueWorktreeService(repository, authorization);
    }

    private static FileManagerLauncher launcher(WorktreeSessionRepository repository) {
        return new FileManagerLauncher(new SessionRegistry(repository));
    }

    /** A launcher that records each file-manager command in {@code launched} instead of spawning it (#784). */
    private static FileManagerLauncher launcher(WorktreeSessionRepository repository, List<String[]> launched) {
        return new FileManagerLauncher(new SessionRegistry(repository), command -> launched.add(command));
    }

    /**
     * Spawns the harmless, instantly-exiting {@code true} instead of code-server
     * itself, with a stub listener on the port it was told to bind to (#776) so
     * {@code CodeServerService.start}'s own wait for a connection succeeds.
     */
    private static CodeServerService codeServerService(WorktreeSessionRepository repository) {
        return new CodeServerService(new SessionRegistry(repository), Path.of("/unused/code-server"),
                command -> {
                    for (String arg : command) {
                        if (arg.startsWith("127.0.0.1:")) {
                            CODE_SERVER_STUBS.add(new ServerSocket(Integer.parseInt(arg.substring("127.0.0.1:".length()))));
                            break;
                        }
                    }
                    return new ProcessBuilder("true").start();
                });
    }
}
