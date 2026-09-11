package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.process.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Fetches issues and PRs by running gh as a subprocess, scoped to one project (#81):
 * every call runs with {@code workingDirectory} as its cwd, so gh resolves the repo
 * from that directory's own git remote (the same auto-detection that already worked
 * for the engine's own checkout, now applied per project) — never an explicit
 * {@code --repo}, which would need parsing an owner/repo out of an arbitrary git URL
 * string. {@code token}, when present, is passed as {@code GH_TOKEN} so the call
 * authenticates as that project's own identity instead of whatever `gh auth login`
 * session the host happens to have; {@code null} falls back to that ambient session
 * (exactly today's single-project behavior, for a project with no token stored).
 *
 * <p>Every call is bounded (#763): stdout and stderr are drained on their own threads
 * so neither pipe can fill and stall gh, and a call that has not finished within
 * {@link #DEFAULT_TIMEOUT} is killed — descendants first, since gh runs git underneath
 * — and reported as a {@link GhUnavailableException}, so a stalled connection to
 * GitHub costs the scheduled poll one minute at most rather than however long the
 * kernel takes to give up on the socket.
 */
public class CliGhClient implements GhClient {

    private static final Logger log = LoggerFactory.getLogger(CliGhClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * How long one gh call may take before it is killed. Generous on purpose: the
     * two {@code --limit 1000} lists run against a large repo can legitimately take
     * tens of seconds over a slow link, and a timeout that fires on a healthy call
     * would report a phantom outage. Shared with {@link CliReleaseClient}.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final Path workingDirectory;
    private final String token;
    private final String executable;
    private final Duration timeout;

    public CliGhClient(Path workingDirectory, String token) {
        this(workingDirectory, token, "gh", DEFAULT_TIMEOUT);
    }

    /** Test-only: substitutes a fake executable for {@code gh} on PATH, and a shorter timeout. */
    CliGhClient(Path workingDirectory, String token, String executable, Duration timeout) {
        this.workingDirectory = workingDirectory;
        this.token = token;
        this.executable = executable;
        this.timeout = timeout;
    }

    @Override
    public List<GhIssue> issues() {
        String json = run("issue", "list", "--state", "all", "--limit", "1000",
                "--json", "number,title,state,labels,body,createdAt,updatedAt,parent");
        try {
            List<GhIssue> result = new ArrayList<>();
            for (JsonNode issue : MAPPER.readTree(json)) {
                result.add(toIssue(issue));
            }
            return result;
        } catch (IOException e) {
            throw new GhUnavailableException("Could not parse gh issue list output", e);
        }
    }

    @Override
    public List<GhPullRequest> pullRequests() {
        String json = run("pr", "list", "--state", "all", "--limit", "1000",
                "--json", "number,title,state,isDraft,headRefName");
        try {
            List<GhPullRequest> result = new ArrayList<>();
            for (JsonNode pr : MAPPER.readTree(json)) {
                result.add(toPullRequest(pr));
            }
            return result;
        } catch (IOException e) {
            throw new GhUnavailableException("Could not parse gh pr list output", e);
        }
    }

    @Override
    public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
        try {
            String json = run("pr", "view", String.valueOf(number),
                    "--json", "number,reviews,statusCheckRollup");
            return Optional.of(toPullRequestDetail(MAPPER.readTree(json)));
        } catch (GhUnavailableException | IOException e) {
            // A nonexistent PR number and a real gh failure look the same from here
            // (both are a nonzero exit / unparseable output); either way, "no detail
            // available" is the right answer for a best-effort popup.
            log.debug("Could not fetch detail for PR {}", number, e);
            return Optional.empty();
        }
    }

    private static GhIssue toIssue(JsonNode issue) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : issue.path("labels")) {
            labels.add(label.path("name").asText());
        }
        JsonNode parentNode = issue.path("parent");
        Integer parent = parentNode.has("number") ? parentNode.path("number").asInt() : null;
        return new GhIssue(
                issue.path("number").asInt(),
                issue.path("title").asText(),
                issue.path("state").asText(),
                labels,
                issue.path("body").asText(""),
                issue.path("createdAt").asText(""),
                issue.path("updatedAt").asText(""),
                parent);
    }

    private static GhPullRequest toPullRequest(JsonNode pr) {
        return new GhPullRequest(
                pr.path("number").asInt(),
                pr.path("title").asText(),
                pr.path("state").asText(),
                pr.path("isDraft").asBoolean(false),
                pr.path("headRefName").asText(""));
    }

    static GhPullRequestDetail toPullRequestDetail(JsonNode pr) {
        int reviewCount = pr.path("reviews").size();
        int pass = 0;
        int fail = 0;
        int pending = 0;
        List<CheckRun> runs = new ArrayList<>();
        for (JsonNode check : latestPerCheck(pr.path("statusCheckRollup"))) {
            String conclusion = check.path("conclusion").asText("");
            if (conclusion.equals("SKIPPED") || conclusion.equals("NEUTRAL")) {
                // Excluded from the counts rather than surfaced as its own category
                // (a legitimately-skipped job, e.g. mac-lifecycle's decide/skip pattern).
                continue;
            }
            String state = switch (conclusion) {
                case "SUCCESS" -> CheckRun.PASSING;
                case "" -> CheckRun.PENDING;
                default -> CheckRun.FAILING;
            };
            switch (state) {
                case CheckRun.PASSING -> pass++;
                case CheckRun.PENDING -> pending++;
                default -> fail++;
            }
            runs.add(new CheckRun(checkName(check), state, checkUrl(check)));
        }
        return new GhPullRequestDetail(pr.path("number").asInt(), reviewCount,
                new ChecksSummary(pass, fail, pending, List.copyOf(runs)));
    }

    /**
     * The rollup carries every check-run ever posted to the PR, including stale
     * entries from a superseded, cancelled, or re-run workflow run; {@code gh pr
     * checks} and GitHub's own PR UI collapse these to the latest run per
     * (workflowName, name). Insertion order is each key's first occurrence, so the
     * result still reads in the order GitHub returned it.
     */
    private static List<JsonNode> latestPerCheck(JsonNode rollup) {
        LinkedHashMap<String, JsonNode> latest = new LinkedHashMap<>();
        for (JsonNode check : rollup) {
            String key = check.path("workflowName").asText("") + "" + checkName(check);
            JsonNode current = latest.get(key);
            if (current == null || recency(check).compareTo(recency(current)) >= 0) {
                latest.put(key, check);
            }
        }
        return List.copyOf(latest.values());
    }

    /** {@code completedAt}, falling back to {@code startedAt}; both are ISO-8601 UTC, so string order is time order. */
    private static String recency(JsonNode check) {
        String completedAt = check.path("completedAt").asText("");
        return completedAt.isBlank() ? check.path("startedAt").asText("") : completedAt;
    }

    /** A check run calls it "name"; a status context (an older-style check) calls it "context". */
    private static String checkName(JsonNode check) {
        String name = check.path("name").asText("");
        return name.isBlank() ? check.path("context").asText("") : name;
    }

    /** Same split for the link: "detailsUrl" on a check run, "targetUrl" on a status context. */
    private static String checkUrl(JsonNode check) {
        String url = check.path("detailsUrl").asText("");
        if (url.isBlank()) {
            url = check.path("targetUrl").asText("");
        }
        return url.isBlank() ? null : url;
    }

    private String run(String... arguments) {
        // Checked up front (#671): ProcessBuilder.start() reports a missing working
        // directory with the same "error=2, No such file or directory" it reports for a
        // missing executable, and the catch below would blame gh's PATH for it.
        if (!Files.isDirectory(workingDirectory)) {
            throw new GhUnavailableException(
                    "Could not run gh: the project directory " + workingDirectory + " does not exist", null);
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
            if (token != null && !token.isBlank()) {
                builder.environment().put("GH_TOKEN", token);
            }
            ProcessOutcome outcome = runBounded(builder, timeout);
            if (outcome.failed()) {
                throw new GhUnavailableException("gh exited " + outcome.exitCode() + ": " + outcome.describe(), null);
            }
            return outcome.stdout();
        } catch (ProcessTimedOut e) {
            throw new GhUnavailableException(e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GhUnavailableException("Interrupted while running gh", e);
        } catch (IOException e) {
            throw new GhUnavailableException("Could not run gh — is it installed and on PATH?", e);
        }
    }

    /**
     * Runs {@code builder}'s process to completion, draining stdout and stderr
     * concurrently, and gives up after {@code timeout} (#763). Reading one stream to
     * EOF before touching the other — what this class did before — deadlocks the moment
     * the other stream fills its pipe, and an unbounded {@code waitFor()} lets one
     * stalled socket hold the caller for as long as the kernel's own retries take.
     *
     * <p>On timeout the process's descendants are killed before the process itself:
     * gh runs {@code git} underneath to resolve the repo from the cwd, and killing only
     * the parent would leave a hung child orphaned and, worse, still holding the
     * output pipes open. The killed process is waited for so its pid is reaped before
     * this returns — a caller sees no lingering child. Package-private so
     * {@link CliReleaseClient}, the engine's other gh caller, runs its process the
     * same way.
     *
     * @throws ProcessTimedOut when the process, or its output, did not finish in time
     *         — it has been killed by the time this is thrown
     */
    static ProcessOutcome runBounded(ProcessBuilder builder, Duration timeout)
            throws IOException, InterruptedException, ProcessTimedOut {
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = builder.start();
        StreamDrain stdout = StreamDrain.start(process.getInputStream(), "stdout");
        StreamDrain stderr = StreamDrain.start(process.getErrorStream(), "stderr");
        try {
            if (!process.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                throw new ProcessTimedOut(builder, timeout, "did not exit");
            }
            // The pipes reach EOF only once every writer has closed them -- normally
            // with the process itself, but a child it left behind could keep them open,
            // so the drain is bounded by the same deadline.
            if (!stdout.join(remainingNanos(deadline)) || !stderr.join(remainingNanos(deadline))) {
                throw new ProcessTimedOut(builder, timeout, "exited but something it started still holds its output open");
            }
            return new ProcessOutcome(process.exitValue(), stdout.text(), stderr.text());
        } catch (ProcessTimedOut | InterruptedException e) {
            kill(process);
            throw e;
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** Descendants first (they would otherwise be orphaned, still holding the pipes), then the process, then reap it. */
    private static void kill(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    /** A gh call that ran past its timeout and has been killed. Message is ready to show to a human. */
    static final class ProcessTimedOut extends Exception {
        ProcessTimedOut(ProcessBuilder builder, Duration timeout, String what) {
            super(String.join(" ", builder.command()) + " " + what + " within " + describe(timeout)
                    + " and was killed");
        }

        private static String describe(Duration timeout) {
            return timeout.toMillis() % 1000 == 0 ? timeout.toSeconds() + " s" : timeout.toMillis() + " ms";
        }
    }

    /** One daemon thread copying a process stream into memory until EOF, so a full pipe never blocks the process. */
    private static final class StreamDrain {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;
        private volatile IOException failure;

        private StreamDrain(InputStream in, String name) {
            this.thread = Thread.ofPlatform().daemon().name("gh-" + name).unstarted(() -> {
                try (in) {
                    in.transferTo(buffer);
                } catch (IOException e) {
                    // silent: kept for text(), which rethrows it on the calling thread
                    failure = e;
                }
            });
        }

        static StreamDrain start(InputStream in, String name) {
            StreamDrain drain = new StreamDrain(in, name);
            drain.thread.start();
            return drain;
        }

        /** {@code true} once the stream hit EOF; {@code false} if it is still open after {@code nanos}. */
        boolean join(long nanos) throws InterruptedException {
            return thread.join(Duration.ofNanos(nanos));
        }

        String text() throws IOException {
            if (failure != null) {
                throw failure;
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
