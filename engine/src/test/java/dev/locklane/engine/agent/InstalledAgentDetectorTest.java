package dev.locklane.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledAgentDetectorTest {

    private static final String[] CANDIDATES = {"claude", "codex", "opencode"};

    @Test
    void findsAnExecutableOnPath(@TempDir Path dir) throws IOException {
        executable(dir, "claude");

        Set<String> found = InstalledAgentDetector.detect(dir.toString(), CANDIDATES);

        assertThat(found).containsExactly("claude");
    }

    @Test
    void ignoresAFileThatIsNotExecutable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("codex");
        Files.createFile(file);
        file.toFile().setExecutable(false);

        Set<String> found = InstalledAgentDetector.detect(dir.toString(), CANDIDATES);

        assertThat(found).isEmpty();
    }

    @Test
    void ignoresADirectoryNamedAfterACandidate(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("opencode"));

        Set<String> found = InstalledAgentDetector.detect(dir.toString(), CANDIDATES);

        assertThat(found).isEmpty();
    }

    @Test
    void resultOrderFollowsCandidateOrderRegardlessOfDiscoveryOrder(@TempDir Path dir) throws IOException {
        executable(dir, "opencode");
        executable(dir, "claude");
        executable(dir, "codex");

        Set<String> found = InstalledAgentDetector.detect(dir.toString(), CANDIDATES);

        assertThat(found).containsExactly("claude", "codex", "opencode");
    }

    @Test
    void searchesEveryDirectoryOnPath(@TempDir Path first, @TempDir Path second) throws IOException {
        executable(second, "claude");

        Set<String> found = InstalledAgentDetector.detect(
                first + File.pathSeparator + second, CANDIDATES);

        assertThat(found).containsExactly("claude");
    }

    @Test
    void aMissingOrBlankPathFindsNothing() {
        assertThat(InstalledAgentDetector.detect(null, CANDIDATES)).isEmpty();
        assertThat(InstalledAgentDetector.detect("", CANDIDATES)).isEmpty();
        assertThat(InstalledAgentDetector.detect("   ", CANDIDATES)).isEmpty();
    }

    private static void executable(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.createFile(file);
        file.toFile().setExecutable(true);
    }
}
