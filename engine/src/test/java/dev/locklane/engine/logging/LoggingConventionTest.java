package dev.locklane.engine.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build-failing half of {@code docs/architecture/logging.md}'s Enforcement
 * section (#546): fails whenever a {@code catch} block under {@code
 * engine/src/main/java} swallows its exception with no log, no rethrow, and no
 * {@code // silent: <why>} comment, or a class using {@code ProcessBuilder}, {@code
 * @Scheduled}, or a raw {@code new Thread(} declares no logger. A PR that
 * reintroduces either fails here, before review — {@link LoggingConventionScanner}
 * does the actual text scan, and its own classification is pinned by {@link
 * LoggingConventionScannerTest}.
 */
class LoggingConventionTest {

    @Test
    void everyCatchAndProcessSpawningOrScheduledClassComplies() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        assertThat(Files.isDirectory(sourceRoot))
                .as("expected to run with the engine module directory as the working directory")
                .isTrue();

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scan(sourceRoot);

        assertThat(violations)
                .as(() -> "logging-convention violations (see docs/architecture/logging.md):\n"
                        + violations.stream().map(Object::toString).collect(Collectors.joining("\n")))
                .isEmpty();
    }
}
