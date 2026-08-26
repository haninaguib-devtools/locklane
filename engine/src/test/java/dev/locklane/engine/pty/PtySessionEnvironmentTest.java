package dev.locklane.engine.pty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #63's done-when: a session's process always sees a real TERM/COLORTERM, even
 * when the caller's own environment (standing in for {@code System.getenv()}, which a
 * test cannot override) has neither — the common case for an engine launched from an
 * IDE run configuration rather than a terminal, which inherits no TERM at all and
 * leaves every CLI running inside a session assuming no color support. An explicit
 * value the caller's environment already carries is left alone rather than clobbered.
 */
class PtySessionEnvironmentTest {

    @Test
    void defaultsTermAndColortermWhenTheCallersEnvironmentHasNeither(@TempDir Path workDir) {
        PtySession session = new PtySession("term-defaults", workDir,
                new String[] {"/bin/sh", "-c", "echo TERM=$TERM COLORTERM=$COLORTERM"}, Map.of(), 80, 24);

        waitUntil(() -> session.bufferedOutput().contains("TERM=xterm-256color COLORTERM=truecolor"),
                Duration.ofSeconds(5));
    }

    @Test
    void keepsAnExplicitTermInsteadOfOverridingIt(@TempDir Path workDir) {
        PtySession session = new PtySession("term-explicit", workDir,
                new String[] {"/bin/sh", "-c", "echo TERM=$TERM COLORTERM=$COLORTERM"},
                Map.of("TERM", "screen-256color"), 80, 24);

        // The caller's TERM survives untouched; COLORTERM is still filled in since the
        // caller never set it.
        waitUntil(() -> session.bufferedOutput().contains("TERM=screen-256color COLORTERM=truecolor"),
                Duration.ofSeconds(5));
    }

    private static void waitUntil(Supplier<Boolean> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }
}
