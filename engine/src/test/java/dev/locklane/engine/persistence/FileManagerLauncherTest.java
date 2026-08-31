package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileManagerLauncherTest {

    @Test
    void resolvesTheConsolesPathAndInvokesTheHostsRevealCommand(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Path worktree = dbDir.resolve("wt1");
        repository.recordAttach("1-174-rename-toggle", worktree, Instant.now(), "alice");
        List<String[]> invocations = new ArrayList<>();
        FileManagerLauncher launcher = new FileManagerLauncher(new SessionRegistry(repository),
                command -> invocations.add(command));

        boolean revealed = launcher.reveal("1-174-rename-toggle");

        assertThat(revealed).isTrue();
        assertThat(invocations).containsExactly(
                FileManagerLauncher.revealCommand(System.getProperty("os.name", ""), worktree));
    }

    @Test
    void returnsFalseAndNeverLaunchesForAnUnknownConsoleId(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        List<String[]> invocations = new ArrayList<>();
        FileManagerLauncher launcher = new FileManagerLauncher(new SessionRegistry(repository),
                command -> invocations.add(command));

        boolean revealed = launcher.reveal("no-such-console");

        assertThat(revealed).isFalse();
        assertThat(invocations).isEmpty();
    }

    @Test
    void picksOpenOnMacOS() {
        assertThat(FileManagerLauncher.revealCommand("Mac OS X", Path.of("/a/b")))
                .containsExactly("open", "/a/b");
    }

    @Test
    void picksExplorerOnWindows() {
        assertThat(FileManagerLauncher.revealCommand("Windows 11", Path.of("C:\\a\\b")))
                .containsExactly("explorer.exe", "C:\\a\\b");
    }

    @Test
    void picksXdgOpenOnLinuxAndAnyOtherOs() {
        assertThat(FileManagerLauncher.revealCommand("Linux", Path.of("/a/b")))
                .containsExactly("xdg-open", "/a/b");
    }
}
