package dev.locklane.engine.logging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link LoggingConventionScanner}'s classification on small inline snippets, so
 * the enforcement mechanism itself cannot silently regress (#546) — the scanner is a
 * text scan, not a compiler, and this is what proves its judgment calls hold before
 * {@link LoggingConventionTest} trusts it against the whole engine tree.
 */
class LoggingConventionScannerTest {

    @Test
    void swallowingACaughtExceptionWithNoLogNoThrowAndNoCommentIsAViolation() {
        String source = """
                class Example {
                    void run() {
                        try {
                            doSomething();
                        } catch (java.io.IOException e) {
                            return;
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("neither logs");
    }

    @Test
    void loggingTheExceptionAsTheLastArgumentPasses() {
        String source = """
                class Example {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Example.class);

                    void run() {
                        try {
                            doSomething();
                        } catch (java.io.IOException e) {
                            log.warn("failed for {}", "thing", e);
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void loggingTheExceptionOnlyByItsMessageStillCountsAsAViolation() {
        String source = """
                class Example {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Example.class);

                    void run() {
                        try {
                            doSomething();
                        } catch (java.io.IOException e) {
                            log.warn("failed: {}", e.getMessage());
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).hasSize(1);
    }

    @Test
    void aSilentCommentAsTheFirstLineOfTheCatchBodyPasses() {
        String source = """
                class Example {
                    void run() {
                        try {
                            doSomething();
                        } catch (java.io.IOException e) {
                            // silent: best-effort cleanup, a leftover file doesn't block anything.
                            return;
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void anExplanatoryCommentWithNoSilentPrefixIsStillAViolation() {
        String source = """
                class Example {
                    void run() {
                        try {
                            doSomething();
                        } catch (java.io.IOException e) {
                            // This is fine, nothing to see here.
                            return;
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).hasSize(1);
    }

    @Test
    void rethrowingPasses() {
        String source = """
                class Example {
                    void run() {
                        try {
                            doSomething();
                        } catch (java.io.IOException e) {
                            throw new RuntimeException("wrapped", e);
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void aMultiCatchIsClassifiedByItsOwnVariableName() {
        String source = """
                class Example {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Example.class);

                    void run() {
                        try {
                            doSomething();
                        } catch (RuntimeException | java.io.IOException problem) {
                            log.error("failed", problem);
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void aLiteralBraceInsideALogMessageDoesNotConfuseTheBodyBoundary() {
        String source = """
                class Example {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Example.class);

                    void run() {
                        try {
                            doSomething();
                        } catch (java.io.IOException e) {
                            log.warn("a literal brace: { and a close: }", e);
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void aSilentCommentAfterTheConventionalInterruptFlagResetStillPasses() {
        String source = """
                class Example {
                    void run() {
                        try {
                            doSomething();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            // silent: best-effort, the caller doesn't wait for this.
                            return;
                        }
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void mentioningProcessBuilderOnlyInAJavadocCommentIsNotUsageAndNeedsNoLogger() {
        String source = """
                /**
                 * Every {@code ProcessBuilder} caller in the engine converts its result into
                 * this shape before logging it.
                 */
                class Example {
                    int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void aClassUsingProcessBuilderWithNoLoggerIsAViolation() {
        String source = """
                class Example {
                    void run() throws java.io.IOException {
                        new ProcessBuilder("ls").start();
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message()).contains("no logger");
    }

    @Test
    void aClassUsingProcessBuilderWithALoggerPasses() {
        String source = """
                class Example {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Example.class);

                    void run() throws java.io.IOException {
                        new ProcessBuilder("ls").start();
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }

    @Test
    void aScheduledMethodWithNoLoggerIsAViolation() {
        String source = """
                import org.springframework.scheduling.annotation.Scheduled;

                class Example {
                    @Scheduled(fixedDelay = 1000)
                    void tick() {
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).hasSize(1);
    }

    @Test
    void aRawThreadWithNoLoggerIsAViolation() {
        String source = """
                class Example {
                    void run() {
                        new Thread(() -> {}).start();
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).hasSize(1);
    }

    @Test
    void aFileWithNoCatchAndNoProcessOrScheduleOrThreadHasNoFindingsAtAll() {
        String source = """
                class Example {
                    int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        List<LoggingConventionScanner.Violation> violations = LoggingConventionScanner.scanContent("Example.java", source);

        assertThat(violations).isEmpty();
    }
}
