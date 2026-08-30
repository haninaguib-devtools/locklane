package dev.locklane.engine.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The short, human-readable name each CLI generates for a conversation (#373), so a
 * past-conversation list can say "Fix the sidenav filter" instead of only a tool name
 * and a timestamp. Every supported CLI already produces one; none of them offers an
 * API for it, so each is read where it actually stores it:
 *
 * <ul>
 *   <li><b>Claude</b> appends {@code {"type":"ai-title","aiTitle":...}} lines into the
 *       conversation's own transcript, at
 *       {@code <claudeHome>/projects/<sanitized working directory>/<resumeId>.jsonl}.
 *       The last such line wins — the title is regenerated as a conversation grows.</li>
 *   <li><b>Codex</b> appends {@code {"id","thread_name","updated_at"}} lines to
 *       {@code <codexHome>/session_index.jsonl}, keyed by thread id; last entry per id
 *       wins. This file only exists from Codex CLI v0.150.0 on.</li>
 *   <li><b>OpenCode</b> has a real CLI surface — {@code opencode session list --format
 *       json} — so no private storage is read at all. It answers for the directory it
 *       runs in.</li>
 * </ul>
 *
 * <p><b>A missing title is the normal case, never an error.</b> A conversation too
 * short to have been titled yet, a Codex older than v0.150.0 (no index file at all), a
 * CLI that is not installed, a working directory that has since been removed, output
 * that does not parse — every one of them resolves to "no title", and the list falls
 * back to the tool and captured time it showed before this class existed. Nothing here
 * throws, and nothing here is load-bearing: the reopen path never consults a title.
 *
 * <p>Lookups are made in batches ({@link #titlesFor}) rather than one at a time,
 * because the three mechanisms have very different costs: Codex's index and each
 * OpenCode directory are read once for the whole batch, where a per-row loop would
 * spawn one {@code opencode} process per listed conversation.
 */
@Service
public class ConsoleSessionTitles {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSessionTitles.class);

    static final String CLAUDE = "claude";
    static final String CODEX = "codex";
    static final String OPENCODE = "opencode";

    // How Claude names the per-project directory holding a conversation's transcript:
    // every character that is not a letter or digit becomes '-'. Derived by reading the
    // real directory names on a machine that has them -- e.g.
    // /home/someone/.locklane/workareas/1/repo-371 is stored as
    // -home-someone--locklane-workareas-1-repo-371 (the dot and each slash each become
    // one '-', and existing '-' survive unchanged).
    private static final Pattern NOT_ALNUM = Pattern.compile("[^a-zA-Z0-9]");

    // OpenCode is a real process, unlike the two file reads: bound it so a hung or
    // very slow CLI degrades to "no title" instead of holding the HTTP response open.
    private static final long OPENCODE_TIMEOUT_SECONDS = 10;

    private final ObjectMapper json = new ObjectMapper();
    private final Path claudeHome;
    private final Path codexHome;
    private final OpencodeLister opencodeLister;

    public ConsoleSessionTitles() {
        this(defaultClaudeHome(), defaultCodexHome(), ConsoleSessionTitles::runOpencodeSessionList);
    }

    /** Test seam: the two CLI home directories, and however OpenCode's listing is obtained. */
    ConsoleSessionTitles(Path claudeHome, Path codexHome, OpencodeLister opencodeLister) {
        this.claudeHome = claudeHome;
        this.codexHome = codexHome;
        this.opencodeLister = opencodeLister;
    }

    /** One conversation to look a title up for: which CLI, which id, and where it ran. */
    public record Sighting(String tool, String resumeId, Path workingDirectory) {
    }

    /**
     * The titles known for {@code sightings}, keyed {@code "<tool>:<resumeId>"} — the
     * same key {@link IssueWorktreeService#resumeSessionsForIssue} and
     * {@link ProjectConsoleService#resumeSessionsForProject} already treat as one
     * conversation's identity. A conversation with no title simply has no entry, so a
     * caller reads the map with a default rather than checking for failure.
     */
    public Map<String, String> titlesFor(Collection<Sighting> sightings) {
        Map<String, String> titles = new LinkedHashMap<>();
        List<Sighting> codexSightings = new ArrayList<>();
        Map<Path, List<Sighting>> opencodeByDirectory = new LinkedHashMap<>();

        for (Sighting sighting : sightings) {
            switch (sighting.tool() == null ? "" : sighting.tool()) {
                case CLAUDE -> claudeTitle(sighting)
                        .ifPresent(title -> titles.put(key(sighting), title));
                case CODEX -> codexSightings.add(sighting);
                case OPENCODE -> opencodeByDirectory
                        .computeIfAbsent(sighting.workingDirectory(), directory -> new ArrayList<>())
                        .add(sighting);
                default -> {
                    // A tool this class knows no title mechanism for -- a shell
                    // console, or a CLI added later -- keeps the timestamp fallback.
                }
            }
        }

        if (!codexSightings.isEmpty()) {
            Map<String, String> byThreadId = codexThreadNames();
            for (Sighting sighting : codexSightings) {
                String title = byThreadId.get(sighting.resumeId());
                if (title != null) {
                    titles.put(key(sighting), title);
                }
            }
        }

        opencodeByDirectory.forEach((directory, inDirectory) -> {
            Map<String, String> bySessionId = opencodeTitles(directory);
            for (Sighting sighting : inDirectory) {
                String title = bySessionId.get(sighting.resumeId());
                if (title != null) {
                    titles.put(key(sighting), title);
                }
            }
        });

        return titles;
    }

    private static String key(Sighting sighting) {
        return sighting.tool() + ":" + sighting.resumeId();
    }

    /**
     * The last {@code ai-title} Claude wrote into this conversation's own transcript.
     * The transcript lives under a directory named after the working directory the
     * conversation ran in, so a sighting with no known directory can never be resolved
     * — Claude keys its stored conversations that way and this reproduces the same key.
     */
    private Optional<String> claudeTitle(Sighting sighting) {
        if (sighting.workingDirectory() == null || sighting.resumeId() == null) {
            return Optional.empty();
        }
        Path transcript = claudeHome
                .resolve("projects")
                .resolve(sanitizeWorkingDirectory(sighting.workingDirectory()))
                .resolve(sighting.resumeId() + ".jsonl");
        String title = null;
        try (Stream<String> lines = readLines(transcript)) {
            if (lines == null) {
                return Optional.empty();
            }
            for (String line : (Iterable<String>) lines::iterator) {
                // Cheap reject before parsing: a transcript is mostly large message
                // objects, and only a handful of lines are ever title lines.
                if (!line.contains("\"ai-title\"")) {
                    continue;
                }
                JsonNode node = parse(line);
                if (node == null || !"ai-title".equals(node.path("type").asText())) {
                    continue;
                }
                String candidate = node.path("aiTitle").asText(null);
                if (candidate != null && !candidate.isBlank()) {
                    // Last one wins: Claude re-titles a conversation as it grows.
                    title = candidate;
                }
            }
        }
        return Optional.ofNullable(title);
    }

    /** Claude's own naming for a working directory's transcript folder. */
    static String sanitizeWorkingDirectory(Path workingDirectory) {
        return NOT_ALNUM.matcher(workingDirectory.toString()).replaceAll("-");
    }

    /**
     * Every thread id Codex has recorded a name for, last entry per id winning. Absent
     * entirely on Codex CLI older than v0.150.0, which wrote no index at all — that
     * reads as "no titles", exactly like an index that exists but has no entry yet.
     */
    private Map<String, String> codexThreadNames() {
        Map<String, String> names = new HashMap<>();
        try (Stream<String> lines = readLines(codexHome.resolve("session_index.jsonl"))) {
            if (lines == null) {
                return names;
            }
            for (String line : (Iterable<String>) lines::iterator) {
                JsonNode node = parse(line);
                if (node == null) {
                    continue;
                }
                String id = node.path("id").asText(null);
                String threadName = node.path("thread_name").asText(null);
                if (id != null && threadName != null && !threadName.isBlank()) {
                    names.put(id, threadName);
                }
            }
        }
        return names;
    }

    /**
     * Every titled session OpenCode reports for {@code workingDirectory}. Unlike the
     * other two this asks the CLI rather than reading its storage, so the answer stays
     * correct if OpenCode changes where it keeps things. An empty answer covers all the
     * ordinary failures at once: no sessions here, OpenCode not installed, or the
     * directory gone.
     */
    private Map<String, String> opencodeTitles(Path workingDirectory) {
        Map<String, String> titles = new HashMap<>();
        if (workingDirectory == null || !Files.isDirectory(workingDirectory)) {
            return titles;
        }
        String output = opencodeLister.list(workingDirectory);
        if (output == null || output.isBlank()) {
            return titles;
        }
        JsonNode node = parse(output);
        if (node == null || !node.isArray()) {
            return titles;
        }
        for (JsonNode session : node) {
            String id = session.path("id").asText(null);
            String title = session.path("title").asText(null);
            if (id != null && title != null && !title.isBlank()) {
                titles.put(id, title);
            }
        }
        return titles;
    }

    /** How {@link #opencodeTitles} obtains OpenCode's listing; swapped out in tests. */
    interface OpencodeLister {
        /** Raw stdout, or null/blank when there is nothing to report for any reason. */
        String list(Path workingDirectory);
    }

    private static String runOpencodeSessionList(Path workingDirectory) {
        Process process = null;
        try {
            process = new ProcessBuilder("opencode", "session", "list", "--format", "json")
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(false)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(OPENCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("opencode session list timed out in {}", workingDirectory);
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (IOException e) {
            // Not installed, or not runnable here. Ordinary, not a fault.
            log.debug("Could not run opencode session list in {}: {}", workingDirectory, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    /** Lines of a file, or null when it cannot be read at all — never an exception. */
    private Stream<String> readLines(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.lines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read {}: {}", file, e.toString());
            return null;
        }
    }

    /** Parsed JSON, or null for anything that is not valid JSON — a partly-written line, say. */
    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path defaultClaudeHome() {
        String configured = System.getenv("CLAUDE_CONFIG_DIR");
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".claude");
    }

    private static Path defaultCodexHome() {
        String configured = System.getenv("CODEX_HOME");
        return configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".codex");
    }
}
