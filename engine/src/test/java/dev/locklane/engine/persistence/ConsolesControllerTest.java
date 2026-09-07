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

class ConsolesControllerTest {

    private static final Principal ALICE = () -> "alice";

    /**
     * Stub listeners {@link #codeServerService} binds, kept alive for the class's
     * whole run (#776): an unreferenced {@link ServerSocket} is eligible for the JVM
     * to reclaim -- and close -- before {@code CodeServerService.start}'s own wait for
     * a connection gets to it.
     */
    private static final List<ServerSocket> CODE_SERVER_STUBS = new CopyOnWriteArrayList<>();

    @Test
    void returnsEveryVisibleConsoleAcrossIssuesInTheProjectRegardlessOfWhoAttached(@TempDir Path dbDir) {
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
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.consoles(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-175-bobs-session");
    }

    @Test
    void includesTheProjectsOwnConsolesAlongsideItsIssues(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-console-0a1b2c3d", dbDir.resolve("wt2"), now, "alice");
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.consoles(1, ALICE)).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-console-0a1b2c3d");
    }

    @Test
    void returnsAnEmptyListWithNoOpenConsoles(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.consoles(1, ALICE)).isEmpty();
    }

    @Test
    void revealFailsFastForAConsoleIdOutsideTheCallersProject(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("2-174-not-alices", dbDir.resolve("wt1"), Instant.now(), "alice");
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        // Never reaches FileManagerLauncher at all -- the ownership check refuses
        // before any lookup of a working directory, exactly like an unknown id would.
        assertThat(controller.reveal(1, "2-174-not-alices", ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openIdeReturnsTheProxiedIdePathForAVisibleConsole(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        ResponseEntity<ConsolesController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle", null, loopback(), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The engine's own proxied path (#655), relative and slash-terminated -- never
        // the loopback address the process itself listens on -- carrying the console's
        // worktree as a `folder` query parameter (#776) so a bookmark or a bare refresh
        // still opens this console's own workspace.
        assertThat(response.getBody().url()).isEqualTo(
                "/api/projects/1/consoles/1-174-rename-toggle/ide/?folder="
                        + java.net.URLEncoder.encode(worktree.toString(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void openIdeFailsFastForAConsoleIdOutsideTheCallersProject(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        createProject(dbDir, "bob"); // project 2
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("2-174-not-alices", dbDir.resolve("wt1"), Instant.now(), "alice");
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), new InstalledIdesStore(), mock(DesktopIdeLauncher.class));

        assertThat(controller.openIde(1, "2-174-not-alices", null, loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The visibility rule is the same for every id (#781): a desktop id on a console
        // outside the caller's project is a 404 too, never a 403 or a launch.
        assertThat(controller.openIde(1, "2-174-not-alices", new ConsolesController.OpenIdeRequest("vscode"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openIdeWithCodeServerNamedExplicitlyBehavesExactlyAsWithNoBody(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        ResponseEntity<ConsolesController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new ConsolesController.OpenIdeRequest("code-server"), remote("192.168.1.20"), ALICE);

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
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), store, desktop);

        ResponseEntity<ConsolesController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new ConsolesController.OpenIdeRequest("intellij"), loopback(), ALICE);

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
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        ResponseEntity<ConsolesController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new ConsolesController.OpenIdeRequest("intellij"), remote("192.168.1.20"), ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(desktop, never()).launch(anyString(), any());
    }

    @Test
    void openIdeWithADesktopIdFromALoopbackPeerBehindAProxyIsForbiddenAndLaunchesNothing(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);
        MockHttpServletRequest relayed = loopback();
        relayed.addHeader("X-Forwarded-For", "203.0.113.7");

        ResponseEntity<ConsolesController.OpenIdeResponse> response = controller.openIde(1, "1-174-rename-toggle",
                new ConsolesController.OpenIdeRequest("intellij"), relayed, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(desktop, never()).launch(anyString(), any());
    }

    @Test
    void openIdeWithAnUnknownOrNotInstalledIdIsABadRequestAndLaunchesNothing(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        // "vscode" is a known id, but installedIdesStore() below found only IntelliJ on this host.
        assertThat(controller.openIde(1, "1-174-rename-toggle", new ConsolesController.OpenIdeRequest("vscode"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.openIde(1, "1-174-rename-toggle", new ConsolesController.OpenIdeRequest("emacs"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(desktop, never()).launch(anyString(), any());
    }

    @Test
    void openIdeWithADesktopIdIsNotFoundForAConsoleWithNoKnownWorkingDirectory(@TempDir Path dbDir) {
        createProject(dbDir, "alice"); // project 1
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher desktop = mock(DesktopIdeLauncher.class);
        when(desktop.launch(anyString(), any())).thenReturn(false);
        ConsolesController controller = new ConsolesController(worktreeService(dbDir, repository), launcher(repository),
                codeServerService(repository), installedIdesStore(), desktop);

        assertThat(controller.openIde(1, "1-174-rename-toggle", new ConsolesController.OpenIdeRequest("intellij"),
                loopback(), ALICE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A request straight from a browser on the engine's own machine (#781). */
    private static MockHttpServletRequest loopback() {
        return remote("127.0.0.1");
    }

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
