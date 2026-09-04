package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.process.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 */
public class CliGhClient implements GhClient {

    private static final Logger log = LoggerFactory.getLogger(CliGhClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDirectory;
    private final String token;

    public CliGhClient(Path workingDirectory, String token) {
        this.workingDirectory = workingDirectory;
        this.token = token;
    }

    @Override
    public List<GhIssue> issues() {
        String json = run("gh", "issue", "list", "--state", "all", "--limit", "1000",
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
        String json = run("gh", "pr", "list", "--state", "all", "--limit", "1000",
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
            String json = run("gh", "pr", "view", String.valueOf(number),
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
        for (JsonNode check : pr.path("statusCheckRollup")) {
            String state = switch (check.path("conclusion").asText("")) {
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

    private String run(String... command) {
        // Checked up front (#671): ProcessBuilder.start() reports a missing working
        // directory with the same "error=2, No such file or directory" it reports for a
        // missing executable, and the catch below would blame gh's PATH for it.
        if (!Files.isDirectory(workingDirectory)) {
            throw new GhUnavailableException(
                    "Could not run gh: the project directory " + workingDirectory + " does not exist", null);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
            if (token != null && !token.isBlank()) {
                builder.environment().put("GH_TOKEN", token);
            }
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            ProcessOutcome outcome = new ProcessOutcome(exit, output, error);
            if (outcome.failed()) {
                throw new GhUnavailableException("gh exited " + exit + ": " + outcome.describe(), null);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GhUnavailableException("Interrupted while running gh", e);
        } catch (IOException e) {
            throw new GhUnavailableException("Could not run gh — is it installed and on PATH?", e);
        }
    }
}
