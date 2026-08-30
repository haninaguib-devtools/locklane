package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class ConsoleSessionTitlesTest {

    private static final String CLAUDE_ID = "aaaaaaaa-0000-0000-0000-000000000000";
    private static final String CODEX_ID = "bbbbbbbb-0000-0000-0000-000000000000";
    private static final String OPENCODE_ID = "ses_01ABCDEFGHIJKLMNOPQRSTUVWX";

    @Test
    void readsClaudesLatestGeneratedTitleFromTheConversationsOwnTranscript(@TempDir Path tmp) throws IOException {
        Path workingDirectory = Files.createDirectories(tmp.resolve("repo-console-a1b2c3d4"));
        writeClaudeTranscript(tmp, workingDirectory, CLAUDE_ID, List.of(
                """
                {"type":"user","message":"hello"}""",
                """
                {"type":"ai-title","aiTitle":"First guess at the topic","sessionId":"%s"}""".formatted(CLAUDE_ID),
                """
                {"type":"assistant","message":"hi"}""",
                // Claude re-titles a conversation as it grows: the last line wins.
                """
                {"type":"ai-title","aiTitle":"Fix the sidenav filter","sessionId":"%s"}""".formatted(CLAUDE_ID)));

        Map<String, String> titles = titles(tmp).titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("claude", CLAUDE_ID, workingDirectory)));

        assertThat(titles).containsExactly(entry("claude:" + CLAUDE_ID, "Fix the sidenav filter"));
    }

    @Test
    void hasNoClaudeTitleForAConversationTooShortToHaveBeenTitled(@TempDir Path tmp) throws IOException {
        Path workingDirectory = Files.createDirectories(tmp.resolve("repo-console-a1b2c3d4"));
        writeClaudeTranscript(tmp, workingDirectory, CLAUDE_ID, List.of("""
                {"type":"user","message":"hello"}"""));

        assertThat(titles(tmp).titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("claude", CLAUDE_ID, workingDirectory)))).isEmpty();
    }

    @Test
    void hasNoClaudeTitleWhenTheTranscriptOrTheDirectoryIsGone(@TempDir Path tmp) {
        // The transcript is keyed by working directory, so a sighting with no known
        // directory can never resolve -- and a directory that was never written has
        // no transcript folder at all.
        assertThat(titles(tmp).titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("claude", CLAUDE_ID, tmp.resolve("never-existed")),
                new ConsoleSessionTitles.Sighting("claude", CLAUDE_ID, null)))).isEmpty();
    }

    @Test
    void survivesAHalfWrittenLineInATranscript(@TempDir Path tmp) throws IOException {
        // A transcript is appended to live, so the last line can be a partial write.
        Path workingDirectory = Files.createDirectories(tmp.resolve("repo-console-a1b2c3d4"));
        writeClaudeTranscript(tmp, workingDirectory, CLAUDE_ID, List.of(
                """
                {"type":"ai-title","aiTitle":"A complete title","sessionId":"%s"}""".formatted(CLAUDE_ID),
                """
                {"type":"ai-title","aiTitle":"trunc"""));

        assertThat(titles(tmp).titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("claude", CLAUDE_ID, workingDirectory))))
                .containsExactly(entry("claude:" + CLAUDE_ID, "A complete title"));
    }

    @Test
    void namesClaudesTranscriptFolderTheWayClaudeItselfDoes() {
        // Confirmed against the real directories on a machine that has them: every
        // character that is not a letter or digit becomes '-', so a leading slash and
        // a dotted directory each contribute one.
        assertThat(ConsoleSessionTitles.sanitizeWorkingDirectory(
                Path.of("/home/someone/.locklane/workareas/1/repo-371")))
                .isEqualTo("-home-someone--locklane-workareas-1-repo-371");
    }

    @Test
    void readsCodexsThreadNameFromItsSessionIndex(@TempDir Path tmp) throws IOException {
        Path codexHome = Files.createDirectories(tmp.resolve("codex"));
        Files.writeString(codexHome.resolve("session_index.jsonl"), """
                {"id":"%s","thread_name":"An earlier name","updated_at":"2026-08-25T12:00:00Z"}
                {"id":"other","thread_name":"Someone else's thread","updated_at":"2026-08-25T12:30:00Z"}
                {"id":"%s","thread_name":"Rework the cleanup sweep","updated_at":"2026-08-25T13:00:00Z"}
                """.formatted(CODEX_ID, CODEX_ID));

        Map<String, String> titles = titles(tmp).titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("codex", CODEX_ID, tmp)));

        // Append-only index: the last entry for an id is its current name.
        assertThat(titles).containsExactly(entry("codex:" + CODEX_ID, "Rework the cleanup sweep"));
    }

    @Test
    void hasNoCodexTitleWhenTheCliPredatesTheSessionIndex(@TempDir Path tmp) {
        // Codex older than v0.150.0 writes no index at all. That must read as "no
        // title yet", exactly like an index with no entry for this thread.
        assertThat(titles(tmp).titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("codex", CODEX_ID, tmp)))).isEmpty();
    }

    @Test
    void readsOpencodesTitleFromItsOwnCliListing(@TempDir Path tmp) throws IOException {
        Path workingDirectory = Files.createDirectories(tmp.resolve("repo-console-a1b2c3d4"));
        ConsoleSessionTitles titles = new ConsoleSessionTitles(tmp.resolve("claude"), tmp.resolve("codex"),
                directory -> """
                        [{"id":"%s","title":"Add the preview endpoint","directory":"%s"},
                         {"id":"ses_somethingelse","title":"Another conversation","directory":"%s"}]
                        """.formatted(OPENCODE_ID, directory, directory));

        assertThat(titles.titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("opencode", OPENCODE_ID, workingDirectory))))
                .containsExactly(entry("opencode:" + OPENCODE_ID, "Add the preview endpoint"));
    }

    @Test
    void asksOpencodeOncePerDirectoryRatherThanOncePerConversation(@TempDir Path tmp) throws IOException {
        // Every other lookup here is a file read; this one is a process. A per-row
        // loop would spawn one CLI per listed conversation.
        Path first = Files.createDirectories(tmp.resolve("repo-console-aaaaaaaa"));
        Path second = Files.createDirectories(tmp.resolve("repo-console-bbbbbbbb"));
        List<Path> asked = new java.util.ArrayList<>();
        ConsoleSessionTitles titles = new ConsoleSessionTitles(tmp.resolve("claude"), tmp.resolve("codex"),
                directory -> {
                    asked.add(directory);
                    return """
                            [{"id":"%s","title":"Shared listing"}]""".formatted(OPENCODE_ID);
                });

        titles.titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("opencode", OPENCODE_ID, first),
                new ConsoleSessionTitles.Sighting("opencode", "ses_second", first),
                new ConsoleSessionTitles.Sighting("opencode", OPENCODE_ID, second)));

        assertThat(asked).containsExactly(first, second);
    }

    @Test
    void hasNoOpencodeTitleWhenTheCliIsMissingOrAnswersWithNothingUsable(@TempDir Path tmp) throws IOException {
        Path workingDirectory = Files.createDirectories(tmp.resolve("repo-console-a1b2c3d4"));
        // Not installed (null), nothing to report (blank), and output that is not the
        // expected JSON all land in the same place: no title, no exception.
        for (String answer : new String[] {null, "", "   ", "not json at all", "{\"unexpected\":\"shape\"}"}) {
            ConsoleSessionTitles titles =
                    new ConsoleSessionTitles(tmp.resolve("claude"), tmp.resolve("codex"), directory -> answer);

            assertThat(titles.titlesFor(List.of(
                    new ConsoleSessionTitles.Sighting("opencode", OPENCODE_ID, workingDirectory)))).isEmpty();
        }
    }

    @Test
    void hasNoTitleForAToolWithNoKnownTitleMechanism(@TempDir Path tmp) {
        // A shell console, or a CLI added after this class was written.
        assertThat(titles(tmp).titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("shell", "whatever", tmp),
                new ConsoleSessionTitles.Sighting(null, "whatever", tmp)))).isEmpty();
    }

    @Test
    void resolvesEachToolInOneMixedBatch(@TempDir Path tmp) throws IOException {
        Path workingDirectory = Files.createDirectories(tmp.resolve("repo-console-a1b2c3d4"));
        writeClaudeTranscript(tmp, workingDirectory, CLAUDE_ID, List.of("""
                {"type":"ai-title","aiTitle":"A Claude conversation","sessionId":"%s"}""".formatted(CLAUDE_ID)));
        Path codexHome = Files.createDirectories(tmp.resolve("codex"));
        Files.writeString(codexHome.resolve("session_index.jsonl"),
                """
                {"id":"%s","thread_name":"A Codex thread"}
                """.formatted(CODEX_ID));
        ConsoleSessionTitles titles = new ConsoleSessionTitles(tmp.resolve("claude"), codexHome,
                directory -> """
                        [{"id":"%s","title":"An OpenCode session"}]""".formatted(OPENCODE_ID));

        assertThat(titles.titlesFor(List.of(
                new ConsoleSessionTitles.Sighting("claude", CLAUDE_ID, workingDirectory),
                new ConsoleSessionTitles.Sighting("codex", CODEX_ID, workingDirectory),
                new ConsoleSessionTitles.Sighting("opencode", OPENCODE_ID, workingDirectory),
                new ConsoleSessionTitles.Sighting("shell", "untitled", workingDirectory))))
                .containsOnly(
                        entry("claude:" + CLAUDE_ID, "A Claude conversation"),
                        entry("codex:" + CODEX_ID, "A Codex thread"),
                        entry("opencode:" + OPENCODE_ID, "An OpenCode session"));
    }

    // ---- the bounded process runner itself (#373) ----
    //
    // Every test above substitutes the OpencodeLister seam, so none of them runs a real
    // process. These do: each one is a way a child process hangs its caller forever if
    // the runner gets it wrong, and the point of the bound is that none of them can.

    @Test
    void returnsWhatACommandActuallyPrinted(@TempDir Path tmp) {
        assertThat(ConsoleSessionTitles.runBounded(tmp, 10, "sh", "-c", "printf '[{\"id\":\"x\"}]'"))
                .isEqualTo("[{\"id\":\"x\"}]");
    }

    @Test
    void hasNoAnswerFromACommandThatFailsOrDoesNotExist(@TempDir Path tmp) {
        assertThat(ConsoleSessionTitles.runBounded(tmp, 10, "sh", "-c", "echo nope; exit 3")).isNull();
        assertThat(ConsoleSessionTitles.runBounded(tmp, 10, "definitely-not-an-installed-cli")).isNull();
    }

    @Test
    void killsACommandThatNeverExitsInsteadOfWaitingForever(@TempDir Path tmp) {
        long startedAt = System.nanoTime();

        assertThat(ConsoleSessionTitles.runBounded(tmp, 1, "sh", "-c", "sleep 60")).isNull();

        // The bound is what returned, not the command finishing.
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void isNotDeadlockedByACommandThatWritesFarMoreThanAPipeHolds(@TempDir Path tmp) {
        // An unread stderr pipe fills at around 64 KB and blocks the child mid-write,
        // forever, with the parent still waiting for stdout that will never come.
        long startedAt = System.nanoTime();

        // ~200 KB of stderr, comfortably past the ~64 KB a pipe holds, then a normal
        // exit: with stderr left unread this never reaches the printf at all.
        String output = ConsoleSessionTitles.runBounded(tmp, 20, "sh", "-c",
                "yes error-line-padding | head -c 200000 >&2; printf ok");

        assertThat(output).isEqualTo("ok");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void doesNotLeaveACommandWaitingOnInputThatWillNeverArrive(@TempDir Path tmp) {
        // The child's stdin is closed straight away, so `cat` reaches end-of-input
        // rather than blocking until the timeout kills it.
        long startedAt = System.nanoTime();

        assertThat(ConsoleSessionTitles.runBounded(tmp, 20, "sh", "-c", "cat; printf done")).isEqualTo("done");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(10));
    }

    /** A lookup with both CLI homes under {@code tmp} and no OpenCode process at all. */
    private static ConsoleSessionTitles titles(Path tmp) {
        return new ConsoleSessionTitles(tmp.resolve("claude"), tmp.resolve("codex"), directory -> null);
    }

    /** Writes a transcript exactly where Claude keeps one for {@code workingDirectory}. */
    private static void writeClaudeTranscript(Path tmp, Path workingDirectory, String resumeId, List<String> lines)
            throws IOException {
        Path folder = tmp.resolve("claude").resolve("projects")
                .resolve(ConsoleSessionTitles.sanitizeWorkingDirectory(workingDirectory));
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(resumeId + ".jsonl"), String.join("\n", lines) + "\n");
    }
}
