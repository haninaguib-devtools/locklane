package dev.locklane.engine.pty;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #102's parsing half in isolation: the shapes a resume id actually appears in
 * — explicit resume commands, a labeled status line, ANSI-wrapped TUI output, ids
 * split across PTY reads — and the shapes that must NOT capture (unattributable
 * uuids in a plain shell, endless TUI redraws of the same text).
 */
class ResumeIdScannerTest {

    private static final String ID = "123e4567-e89b-42d3-a456-426614174000";
    private static final String OPENCODE_ID = "ses_3cf7dd8d4ffeUPfENpVxfFojZ2";

    @Test
    void capturesAClaudeResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        List<ResumeIdScanner.Capture> captures =
                scanner.feed(bytes("Resume this conversation with claude --resume " + ID + "\n"));

        assertThat(captures).containsExactly(new ResumeIdScanner.Capture("claude", ID));
    }

    @Test
    void capturesAClaudeShortFlagResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("claude -r " + ID + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("claude", ID));
    }

    @Test
    void capturesACodexResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        List<ResumeIdScanner.Capture> captures =
                scanner.feed(bytes("To continue this session, run codex resume " + ID + ".\n"));

        assertThat(captures).containsExactly(new ResumeIdScanner.Capture("codex", ID));
    }

    @Test
    void capturesAnOpenCodeResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        List<ResumeIdScanner.Capture> captures =
                scanner.feed(bytes("To continue this session, run opencode --session " + OPENCODE_ID + ".\n"));

        assertThat(captures).containsExactly(new ResumeIdScanner.Capture("opencode", OPENCODE_ID));
    }

    @Test
    void capturesAnOpenCodeShortFlagResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("opencode -s " + OPENCODE_ID + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("opencode", OPENCODE_ID));
    }

    @Test
    void capturesAnOmpResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        List<ResumeIdScanner.Capture> captures =
                scanner.feed(bytes("To continue this session, run omp --resume " + ID + ".\n"));

        assertThat(captures).containsExactly(new ResumeIdScanner.Capture("omp", ID));
    }

    @Test
    void capturesAnOmpShortFlagResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("omp -r " + ID + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("omp", ID));
    }

    @Test
    void capturesAnOmpSessionFlagResumeCommand() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("omp --session " + ID + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("omp", ID));
    }

    @Test
    void anOpenCodeIdsCaseIsPreservedUnlikeAUuids() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);
        String mixedCaseId = "ses_AbCdEf1234567890ABCDEFabcd";

        assertThat(scanner.feed(bytes("opencode --session " + mixedCaseId + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("opencode", mixedCaseId));
    }

    @Test
    void aLabeledSessionIdIsAttributedToTheLaunchCommandsTool() {
        ResumeIdScanner scanner = new ResumeIdScanner(ResumeIdScanner.CODEX);

        assertThat(scanner.feed(bytes("session id: " + ID + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("codex", ID));
    }

    @Test
    void aLabeledSessionIdIsAttributedToOmpWhenThatIsTheLaunchCommandsTool() {
        ResumeIdScanner scanner = new ResumeIdScanner(ResumeIdScanner.OMP);

        assertThat(scanner.feed(bytes("session id: " + ID + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("omp", ID));
    }

    @Test
    void aLabeledSessionIdWithNoToolHintIsIgnored() {
        // A shell console printing "Session ID: <uuid>" could be anything — logs, a
        // web request id — and a wrong tool attribution is worse than no capture.
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("Session ID: " + ID + "\n"))).isEmpty();
    }

    @Test
    void matchesThroughInterleavedAnsiEscapeSequences() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);
        String decorated = "\u001B[2K\u001B[1G\u001B[32mclaude\u001B[0m \u001B[1m--resume\u001B[0m " + ID + "\u001B[K\r\n";

        assertThat(scanner.feed(bytes(decorated)))
                .containsExactly(new ResumeIdScanner.Capture("claude", ID));
    }

    @Test
    void anIdSplitAcrossTwoReadsMatchesOnceTheRestArrives() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("codex resume 123e4567-e89b-42d3-"))).isEmpty();
        assertThat(scanner.feed(bytes("a456-426614174000\n")))
                .containsExactly(new ResumeIdScanner.Capture("codex", ID));
    }

    @Test
    void anEscapeSequenceSplitAcrossTwoReadsHealsInsteadOfBreakingTheId() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("claude --resume 123e4567-e89b-42d3-\u001B["))).isEmpty();
        assertThat(scanner.feed(bytes("0ma456-426614174000\n")))
                .containsExactly(new ResumeIdScanner.Capture("claude", ID));
    }

    @Test
    void theSameIdRedrawnForeverIsReportedExactlyOnce() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);
        byte[] redraw = bytes("claude --resume " + ID + "\n");

        assertThat(scanner.feed(redraw)).hasSize(1);
        assertThat(scanner.feed(redraw)).isEmpty();
        assertThat(scanner.feed(redraw)).isEmpty();
    }

    @Test
    void capturedIdsAreNormalizedToLowercase() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("claude --resume " + ID.toUpperCase() + "\n")))
                .containsExactly(new ResumeIdScanner.Capture("claude", ID));
    }

    @Test
    void ordinaryShellOutputCapturesNothing() {
        ResumeIdScanner scanner = new ResumeIdScanner(null);

        assertThat(scanner.feed(bytes("$ ls -la\ntotal 42\ndrwxr-xr-x 4 dev dev 4096 .\n$ "))).isEmpty();
    }

    @Test
    void toolHintComesFromTheLaunchCommandsExecutableBasename() {
        assertThat(ResumeIdScanner.toolHintFor(new String[] {"claude"})).isEqualTo("claude");
        assertThat(ResumeIdScanner.toolHintFor(new String[] {"/usr/local/bin/codex"})).isEqualTo("codex");
        assertThat(ResumeIdScanner.toolHintFor(new String[] {"/usr/local/bin/opencode"})).isEqualTo("opencode");
        assertThat(ResumeIdScanner.toolHintFor(new String[] {"/usr/local/bin/omp"})).isEqualTo("omp");
        assertThat(ResumeIdScanner.toolHintFor(new String[] {"/bin/sh", "-i"})).isNull();
        assertThat(ResumeIdScanner.toolHintFor(null)).isNull();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
