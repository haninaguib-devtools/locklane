package dev.locklane.engine.ide;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every OS's detection against a fake {@code PATH} and a temp-dir filesystem (#781) — no real host state read. */
class InstalledIdeDetectorTest {

    private static final IdeInfo CODE_SERVER = new IdeInfo("code-server", "code-server", false);
    private static final IdeInfo VSCODE = new IdeInfo("vscode", "VS Code", true);
    private static final IdeInfo INTELLIJ = new IdeInfo("intellij", "IntelliJ IDEA", true);

    @Test
    void findsNothingOnABareHost(@TempDir Path dir) {
        assertThat(InstalledIdeDetector.detect(HostOs.LINUX, dir.toString(), dir.resolve("missing"), List.of()))
                .isEmpty();
        assertThat(InstalledIdeDetector.detect(HostOs.LINUX, null, null, List.of())).isEmpty();
    }

    @Test
    void findsTheBundledCodeServerByItsBinaryOnAnyOs(@TempDir Path dir) throws IOException {
        Path binary = executable(dir.resolve("code-server").resolve("bin"), "code-server");

        for (HostOs os : HostOs.values()) {
            assertThat(InstalledIdeDetector.detect(os, "", binary, List.of()))
                    .containsExactly(InstalledIde.onPath(CODE_SERVER));
        }
    }

    @Test
    void ignoresACodeServerBinaryThatIsNotExecutableOrIsADirectory(@TempDir Path dir) throws IOException {
        Path plain = dir.resolve("plain");
        Files.createFile(plain);
        plain.toFile().setExecutable(false);
        Path directory = Files.createDirectory(dir.resolve("code-server"));

        assertThat(InstalledIdeDetector.detect(HostOs.LINUX, "", plain, List.of())).isEmpty();
        assertThat(InstalledIdeDetector.detect(HostOs.LINUX, "", directory, List.of())).isEmpty();
    }

    @Test
    void onLinuxFindsCodeAndIdeaOnPath(@TempDir Path bin) throws IOException {
        executable(bin, "code");
        executable(bin, "idea");

        assertThat(InstalledIdeDetector.detect(HostOs.LINUX, bin.toString(), null, List.of()))
                .containsExactly(InstalledIde.onPath(VSCODE), InstalledIde.onPath(INTELLIJ));
    }

    @Test
    void onLinuxIgnoresTheWindowsAndMacNames(@TempDir Path bin, @TempDir Path applications) throws IOException {
        executable(bin, "code.cmd");
        executable(bin, "idea64.exe");
        Files.createDirectory(applications.resolve("Visual Studio Code.app"));

        assertThat(InstalledIdeDetector.detect(HostOs.LINUX, bin.toString(), null, List.of(applications))).isEmpty();
    }

    @Test
    void onWindowsFindsCodeCmdAndIdea64Exe(@TempDir Path bin) throws IOException {
        executable(bin, "code.cmd");
        executable(bin, "idea64.exe");

        assertThat(InstalledIdeDetector.detect(HostOs.WINDOWS, bin.toString(), null, List.of()))
                .containsExactly(InstalledIde.onPath(VSCODE), InstalledIde.onPath(INTELLIJ));
    }

    @Test
    void onWindowsIgnoresTheBareLinuxNames(@TempDir Path bin) throws IOException {
        executable(bin, "code");
        executable(bin, "idea");

        assertThat(InstalledIdeDetector.detect(HostOs.WINDOWS, bin.toString(), null, List.of())).isEmpty();
    }

    @Test
    void onMacFindsAppBundlesUnderApplicationsAndRecordsTheAppName(@TempDir Path applications) throws IOException {
        Files.createDirectory(applications.resolve("Visual Studio Code.app"));
        Files.createDirectory(applications.resolve("IntelliJ IDEA CE.app"));

        assertThat(InstalledIdeDetector.detect(HostOs.MAC, "", null, List.of(applications)))
                .containsExactly(new InstalledIde(VSCODE, "Visual Studio Code"),
                        new InstalledIde(INTELLIJ, "IntelliJ IDEA CE"));
    }

    @Test
    void onMacSearchesEveryApplicationsFolderInOrderAndSkipsAMissingOne(@TempDir Path system, @TempDir Path home)
            throws IOException {
        Files.createDirectory(home.resolve("IntelliJ IDEA Ultimate.app"));

        assertThat(InstalledIdeDetector.detect(HostOs.MAC, "", null,
                List.of(system.resolve("no-such-dir"), system, home)))
                .containsExactly(new InstalledIde(INTELLIJ, "IntelliJ IDEA Ultimate"));
    }

    @Test
    void onMacPicksTheFirstBundleByNameWhenSeveralEditionsAreInstalled(@TempDir Path applications)
            throws IOException {
        Files.createDirectory(applications.resolve("IntelliJ IDEA Ultimate.app"));
        Files.createDirectory(applications.resolve("IntelliJ IDEA CE.app"));

        assertThat(InstalledIdeDetector.detect(HostOs.MAC, "", null, List.of(applications)))
                .containsExactly(new InstalledIde(INTELLIJ, "IntelliJ IDEA CE"));
    }

    @Test
    void onMacIgnoresAPlainFileOrAnUnrelatedBundleNamedLikeAnApp(@TempDir Path applications) throws IOException {
        Files.createFile(applications.resolve("Visual Studio Code.app"));
        Files.createDirectory(applications.resolve("IntelliJ IDEA.app.bak"));
        Files.createDirectory(applications.resolve("PyCharm.app"));

        assertThat(InstalledIdeDetector.detect(HostOs.MAC, "", null, List.of(applications))).isEmpty();
    }

    @Test
    void onMacFallsBackToThePathShimWithNoBundle(@TempDir Path bin, @TempDir Path applications) throws IOException {
        executable(bin, "code");
        executable(bin, "idea");

        assertThat(InstalledIdeDetector.detect(HostOs.MAC, bin.toString(), null, List.of(applications)))
                .containsExactly(InstalledIde.onPath(VSCODE), InstalledIde.onPath(INTELLIJ));
    }

    @Test
    void onMacPrefersTheBundleOverThePathShimWhenBothExist(@TempDir Path bin, @TempDir Path applications)
            throws IOException {
        executable(bin, "code");
        Files.createDirectory(applications.resolve("Visual Studio Code.app"));

        assertThat(InstalledIdeDetector.detect(HostOs.MAC, bin.toString(), null, List.of(applications)))
                .containsExactly(new InstalledIde(VSCODE, "Visual Studio Code"));
    }

    @Test
    void resultFollowsTableOrderRegardlessOfWhatIsFoundFirst(@TempDir Path dir) throws IOException {
        Path bin = Files.createDirectory(dir.resolve("bin"));
        executable(bin, "idea");
        executable(bin, "code");
        Path binary = executable(dir.resolve("code-server").resolve("bin"), "code-server");

        assertThat(InstalledIdeDetector.detect(HostOs.LINUX, bin.toString(), binary, List.of()))
                .extracting(InstalledIde::id).containsExactly("code-server", "vscode", "intellij");
    }

    private static Path executable(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.createFile(file);
        file.toFile().setExecutable(true);
        return file;
    }
}
