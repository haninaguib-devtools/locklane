package dev.locklane.engine.ide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Mirrors {@code FileManagerLauncherTest} (#781): an injected runner, the command a pure function, no process spawned. */
class DesktopIdeLauncherTest {

    private static final InstalledIde VSCODE = InstalledIde.onPath(new IdeInfo("vscode", "VS Code", true));
    private static final InstalledIde INTELLIJ = InstalledIde.onPath(new IdeInfo("intellij", "IntelliJ IDEA", true));
    private static final InstalledIde VSCODE_APP = new InstalledIde(new IdeInfo("vscode", "VS Code", true),
            "Visual Studio Code");
    private static final InstalledIde INTELLIJ_APP = new InstalledIde(new IdeInfo("intellij", "IntelliJ IDEA", true),
            "IntelliJ IDEA CE");

    @Test
    void resolvesTheConsolesPathAndInvokesTheIdesLaunchCommand(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        DesktopIdeLauncher launcher = new DesktopIdeLauncher(new SessionRegistry(repository),
                command -> invocations.add(command), "Linux", () -> false);

        boolean launched = launcher.launch("1-174-rename-toggle", INTELLIJ);

        assertThat(launched).isTrue();
        assertThat(invocations).containsExactly(new String[] {"idea", worktree.toString()});
    }

    @Test
    void onLinuxWithSystemdRunTheLaunchIsWrappedInATransientScope(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        DesktopIdeLauncher launcher = new DesktopIdeLauncher(new SessionRegistry(repository),
                command -> invocations.add(command), "Linux", () -> true);

        launcher.launch("1-174-rename-toggle", VSCODE);

        assertThat(invocations).containsExactly(new String[] {
                "systemd-run", "--user", "--scope", "--quiet", "--collect", "code", worktree.toString()});
    }

    @Test
    void returnsFalseAndNeverLaunchesForAnUnknownConsoleId(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        List<String[]> invocations = new ArrayList<>();
        DesktopIdeLauncher launcher = new DesktopIdeLauncher(new SessionRegistry(repository),
                command -> invocations.add(command), "Linux", () -> true);

        boolean launched = launcher.launch("no-such-console", VSCODE);

        assertThat(launched).isFalse();
        assertThat(invocations).isEmpty();
    }

    @Test
    void wrapsAFailureToSpawn(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), Instant.now(), "alice");
        DesktopIdeLauncher launcher = new DesktopIdeLauncher(new SessionRegistry(repository),
                command -> { throw new IOException("no such file"); }, "Linux", () -> false);

        assertThatThrownBy(() -> launcher.launch("1-174-rename-toggle", VSCODE))
                .isInstanceOf(DesktopIdeLauncher.DesktopIdeLaunchException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    // -- launchCommand: a pure function of OS name, IDE and path, per OS --

    @Test
    void onLinuxRunsTheBareExecutable() {
        assertThat(DesktopIdeLauncher.launchCommand("Linux", VSCODE, Path.of("/a/b")))
                .containsExactly("code", "/a/b");
        assertThat(DesktopIdeLauncher.launchCommand("Linux", INTELLIJ, Path.of("/a/b")))
                .containsExactly("idea", "/a/b");
    }

    @Test
    void onWindowsRunsACmdScriptViaCmdCAndAnExeDirectly() {
        assertThat(DesktopIdeLauncher.launchCommand("Windows 11", VSCODE, Path.of("C:\\a\\b")))
                .containsExactly("cmd", "/c", "code.cmd", "C:\\a\\b");
        assertThat(DesktopIdeLauncher.launchCommand("Windows 11", INTELLIJ, Path.of("C:\\a\\b")))
                .containsExactly("idea64.exe", "C:\\a\\b");
    }

    @Test
    void onMacOpensTheAppBundleByNameWhenFoundUnderApplications() {
        assertThat(DesktopIdeLauncher.launchCommand("Mac OS X", VSCODE_APP, Path.of("/a/b")))
                .containsExactly("open", "-a", "Visual Studio Code", "/a/b");
        assertThat(DesktopIdeLauncher.launchCommand("Mac OS X", INTELLIJ_APP, Path.of("/a/b")))
                .containsExactly("open", "-a", "IntelliJ IDEA CE", "/a/b");
    }

    @Test
    void onMacFallsBackToThePathShimWithNoBundle() {
        assertThat(DesktopIdeLauncher.launchCommand("Mac OS X", VSCODE, Path.of("/a/b")))
                .containsExactly("code", "/a/b");
        assertThat(DesktopIdeLauncher.launchCommand("Mac OS X", INTELLIJ, Path.of("/a/b")))
                .containsExactly("idea", "/a/b");
    }

    @Test
    void refusesToBuildACommandForCodeServerOrAnUnknownId() {
        InstalledIde codeServer = InstalledIde.onPath(new IdeInfo("code-server", "code-server", false));
        assertThatThrownBy(() -> DesktopIdeLauncher.launchCommand("Linux", codeServer, Path.of("/a")))
                .isInstanceOf(IllegalArgumentException.class);
        InstalledIde unknown = InstalledIde.onPath(new IdeInfo("emacs", "Emacs", true));
        assertThatThrownBy(() -> DesktopIdeLauncher.launchCommand("Linux", unknown, Path.of("/a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -- detached: systemd-run only on Linux, only when present --

    @Test
    void detachesThroughSystemdRunOnLinuxWhenItIsOnPath() {
        assertThat(DesktopIdeLauncher.detached("Linux", true, new String[] {"code", "/a/b"}))
                .containsExactly("systemd-run", "--user", "--scope", "--quiet", "--collect", "code", "/a/b");
    }

    @Test
    void spawnsPlainlyOnLinuxWithoutSystemdRunAndOnEveryOtherOs() {
        String[] command = {"code", "/a/b"};
        assertThat(DesktopIdeLauncher.detached("Linux", false, command)).isSameAs(command);
        assertThat(DesktopIdeLauncher.detached("Mac OS X", true, command)).isSameAs(command);
        assertThat(DesktopIdeLauncher.detached("Windows 11", true, command)).isSameAs(command);
    }
}
