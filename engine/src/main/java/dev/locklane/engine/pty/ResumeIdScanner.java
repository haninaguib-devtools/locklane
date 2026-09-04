package dev.locklane.engine.pty;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches one session's output stream for a Claude/Codex/OpenCode/omp resume id (#102,
 * #295, #681) — the id that {@code claude --resume <id>}, {@code codex resume <id>},
 * {@code opencode --session <id>}, or {@code omp --resume <id>} accepts. Current CLI
 * versions print no id at plain startup; ids surface later — a status screen, a
 * crash/exit hint — so the scanner watches the whole stream for the session's lifetime,
 * not a startup banner.
 *
 * <p>Terminal UIs interleave ANSI escape sequences with text and split lines across
 * PTY reads, so raw chunks are accumulated into a bounded rolling window and escape
 * sequences are stripped over the whole window before matching — a sequence split
 * across two reads heals once the rest arrives. Redraw loops repeat the same text
 * endlessly; each (tool, id) pair is reported exactly once.
 *
 * <p>An explicit resume command names its own tool. A bare labeled form
 * ("Session ID: &lt;uuid&gt;") is attributed to {@code toolHint} — the tool the
 * session's launch command names — and ignored when there is none: a shell console
 * printing an unattributable uuid is noise, not a capture.
 *
 * <p>Not thread-safe; {@link #feed} is only ever called from the session's single
 * drain thread.
 */
final class ResumeIdScanner {

    static final String CLAUDE = "claude";
    static final String CODEX = "codex";
    static final String OPENCODE = "opencode";
    static final String OMP = "omp";

    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    // OpenCode's own ids are ULID-based (`ses_` + 26 base32 characters), not UUIDs; the
    // length is allowed a little slack rather than pinned exactly, since it was
    // confirmed from OpenCode's public source references rather than a live account.
    private static final String OPENCODE_ID_PREFIX = "ses_";
    private static final String OPENCODE_ID = OPENCODE_ID_PREFIX + "[0-9A-Za-z]{20,32}";
    private static final String ANY_ID = "(?:" + UUID + "|" + OPENCODE_ID + ")";

    private static final Pattern CLAUDE_RESUME_COMMAND =
            Pattern.compile("(?i)\\bclaude\\s+(?:--resume|-r)\\s+(" + UUID + ")");
    private static final Pattern CODEX_RESUME_COMMAND =
            Pattern.compile("(?i)\\bcodex\\s+resume\\s+(" + UUID + ")");
    private static final Pattern OPENCODE_RESUME_COMMAND =
            Pattern.compile("(?i)\\bopencode\\s+(?:--session|-s)\\s+(" + OPENCODE_ID + ")");
    // omp's own ids are UUIDv7 (confirmed from its session storage source, which mints
    // them via `Bun.randomUUIDv7()`) — the same shape as Claude/Codex, so they reuse
    // UUID rather than a fourth id pattern. `--resume`, `-r`, and `--session` are all
    // equivalent per omp's CLI reference.
    private static final Pattern OMP_RESUME_COMMAND =
            Pattern.compile("(?i)\\bomp\\s+(?:--resume|-r|--session)\\s+(" + UUID + ")");
    private static final Pattern LABELED_SESSION_ID =
            Pattern.compile("(?i)\\bsession[ _-]?id\\s*[:=]?\\s*(" + ANY_ID + ")");

    // Applied in order: OSC (title/color sequences, string terminator BEL or ESC \),
    // CSI (parameters + intermediates + final byte), two-byte charset designations,
    // then any remaining ESC + final byte (ESC 7, ESC 8, ESC M, ...). What they
    // leave behind is the plain text a human would read off the screen, in stream
    // order.
    private static final Pattern[] ESCAPE_SEQUENCES = {
            Pattern.compile("\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)"),
            Pattern.compile("\\u001B\\[[0-?]*[ -/]*[@-~]"),
            Pattern.compile("\\u001B[()][0-9A-Za-z]"),
            Pattern.compile("\\u001B[0-~]"),
    };

    // Enough to hold the region around any freshly printed id while TUI redraws
    // churn; trimming only ever discards text old enough to have been matched long
    // ago if it was ever going to match.
    private static final int WINDOW_CHARS = 16 * 1024;

    record Capture(String tool, String resumeId) {
    }

    private final String toolHint;
    private final StringBuilder window = new StringBuilder();
    private final Set<String> reported = new HashSet<>();

    /** {@code toolHint}: {@link #CLAUDE}, {@link #CODEX}, {@link #OPENCODE}, {@link #OMP}, or null when the launch command names none of them. */
    ResumeIdScanner(String toolHint) {
        this.toolHint = toolHint;
    }

    /** The tool a launch command names, from its executable's basename, or null. */
    static String toolHintFor(String[] command) {
        if (command == null || command.length == 0 || command[0] == null) {
            return null;
        }
        String executable = command[0];
        String basename = executable.substring(executable.lastIndexOf('/') + 1).toLowerCase();
        if (basename.startsWith(CLAUDE)) {
            return CLAUDE;
        }
        if (basename.startsWith(CODEX)) {
            return CODEX;
        }
        if (basename.startsWith(OPENCODE)) {
            return OPENCODE;
        }
        if (basename.startsWith(OMP)) {
            return OMP;
        }
        return null;
    }

    /** Scans the next output chunk; returns only (tool, id) pairs not reported before. */
    List<Capture> feed(byte[] chunk) {
        // A multi-byte character split across reads decodes to replacement chars,
        // which is harmless here: every pattern is pure ASCII.
        window.append(new String(chunk, StandardCharsets.UTF_8));
        if (window.length() > WINDOW_CHARS) {
            window.delete(0, window.length() - WINDOW_CHARS);
        }
        String plain = stripEscapes(window);

        List<Capture> captures = new ArrayList<>();
        collect(captures, CLAUDE_RESUME_COMMAND, plain, CLAUDE);
        collect(captures, CODEX_RESUME_COMMAND, plain, CODEX);
        collect(captures, OPENCODE_RESUME_COMMAND, plain, OPENCODE);
        collect(captures, OMP_RESUME_COMMAND, plain, OMP);
        if (toolHint != null) {
            collect(captures, LABELED_SESSION_ID, plain, toolHint);
        }
        return captures;
    }

    private void collect(List<Capture> captures, Pattern pattern, String plain, String tool) {
        Matcher matcher = pattern.matcher(plain);
        while (matcher.find()) {
            // A UUID (Claude/Codex) is case-insensitive by definition and its
            // canonical form is lowercase; OpenCode's ULID-based id is not, and
            // altering its case would make it not match the session it names.
            String captured = matcher.group(1);
            String resumeId = captured.toLowerCase().startsWith(OPENCODE_ID_PREFIX) ? captured : captured.toLowerCase();
            if (reported.add(tool + ":" + resumeId)) {
                captures.add(new Capture(tool, resumeId));
            }
        }
    }

    private static String stripEscapes(CharSequence raw) {
        String plain = raw.toString();
        for (Pattern sequence : ESCAPE_SEQUENCES) {
            plain = sequence.matcher(plain).replaceAll("");
        }
        // Carriage returns and bells sit inside redrawn lines; a stray one must not
        // split an id in two.
        return plain.replace("\r", "").replace("\u0007", "");
    }
}
