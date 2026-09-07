package dev.locklane.engine.ide;

import dev.locklane.engine.agent.InstalledAgentDetector;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which rows of {@link KnownIdes#TABLE} are installed on this host (#781), in
 * table order, from the inputs alone — the OS, the {@code PATH} string, where the
 * bundled code-server binary would be, and the Applications folders to look in — so a
 * test can hand it a fake {@code PATH} and a temp-dir filesystem for any OS. Cheap like
 * {@link InstalledAgentDetector} (directory listings, no process spawned), so it runs
 * once at boot.
 */
final class InstalledIdeDetector {

    private static final Logger log = LoggerFactory.getLogger(InstalledIdeDetector.class);

    private InstalledIdeDetector() {
    }

    /**
     * @param codeServerBinary where {@code CodeServerService} would run code-server from;
     *        installed when it is an executable file
     * @param applicationDirs the macOS Applications folders, direct children only;
     *        ignored on any other OS
     */
    static List<InstalledIde> detect(HostOs os, String pathEnv, Path codeServerBinary, List<Path> applicationDirs) {
        List<InstalledIde> found = new ArrayList<>();
        for (KnownIdes.Definition definition : KnownIdes.TABLE) {
            if (definition == KnownIdes.CODE_SERVER) {
                if (isExecutableFile(codeServerBinary)) {
                    found.add(InstalledIde.onPath(definition.info()));
                }
                continue;
            }
            detectDesktop(os, pathEnv, applicationDirs, definition).ifPresent(found::add);
        }
        return List.copyOf(found);
    }

    private static Optional<InstalledIde> detectDesktop(HostOs os, String pathEnv, List<Path> applicationDirs,
            KnownIdes.Definition definition) {
        return switch (os) {
            case LINUX -> onPath(pathEnv, definition.linuxExecutable(), definition.info());
            case WINDOWS -> onPath(pathEnv, definition.windowsExecutable(), definition.info());
            // The app bundle first: when both exist, launching goes through `open -a`
            // (KnownIdes.launchCommand), the issue's stated form for a bundle found
            // under Applications; the PATH shim is the fallback.
            case MAC -> findApp(applicationDirs, definition.macAppGlob())
                    .map(appName -> new InstalledIde(definition.info(), appName))
                    .or(() -> onPath(pathEnv, definition.macExecutable(), definition.info()));
        };
    }

    private static Optional<InstalledIde> onPath(String pathEnv, String executable, IdeInfo info) {
        return InstalledAgentDetector.detect(pathEnv, new String[] {executable}).isEmpty()
                ? Optional.empty()
                : Optional.of(InstalledIde.onPath(info));
    }

    /**
     * The name, without {@code .app}, of the first directory (lexicographically, across
     * {@code applicationDirs} in order) whose file name matches {@code glob} — a
     * best-effort pick where several editions are installed side by side.
     */
    private static Optional<String> findApp(List<Path> applicationDirs, String glob) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        for (Path applicationDir : applicationDirs) {
            if (!Files.isDirectory(applicationDir)) {
                continue;
            }
            try (Stream<Path> children = Files.list(applicationDir)) {
                Optional<String> match = children
                        .filter(Files::isDirectory)
                        .map(child -> child.getFileName().toString())
                        .filter(name -> matcher.matches(Path.of(name)))
                        .sorted()
                        .findFirst()
                        .map(name -> name.substring(0, name.length() - ".app".length()));
                if (match.isPresent()) {
                    return match;
                }
            } catch (IOException e) {
                log.debug("Could not list {} while probing for {}", applicationDir, glob, e);
            }
        }
        return Optional.empty();
    }

    private static boolean isExecutableFile(Path path) {
        return path != null && Files.isRegularFile(path) && Files.isExecutable(path);
    }
}
