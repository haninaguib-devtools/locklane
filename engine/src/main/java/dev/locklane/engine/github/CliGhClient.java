package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Fetches issues and PRs by running gh as a subprocess. */
public class CliGhClient implements GhClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<GhIssue> issues() {
        String json = run("gh", "issue", "list", "--state", "all", "--limit", "1000",
                "--json", "number,title,state,labels,body,createdAt,updatedAt");
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
            return Optional.empty();
        }
    }

    private static GhIssue toIssue(JsonNode issue) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : issue.path("labels")) {
            labels.add(label.path("name").asText());
        }
        return new GhIssue(
                issue.path("number").asInt(),
                issue.path("title").asText(),
                issue.path("state").asText(),
                labels,
                issue.path("body").asText(""),
                issue.path("createdAt").asText(""),
                issue.path("updatedAt").asText(""));
    }

    private static GhPullRequest toPullRequest(JsonNode pr) {
        return new GhPullRequest(
                pr.path("number").asInt(),
                pr.path("title").asText(),
                pr.path("state").asText(),
                pr.path("isDraft").asBoolean(false),
                pr.path("headRefName").asText(""));
    }

    private static GhPullRequestDetail toPullRequestDetail(JsonNode pr) {
        int reviewCount = pr.path("reviews").size();
        int pass = 0;
        int fail = 0;
        int pending = 0;
        for (JsonNode check : pr.path("statusCheckRollup")) {
            switch (check.path("conclusion").asText("")) {
                case "SUCCESS" -> pass++;
                case "" -> pending++;
                default -> fail++;
            }
        }
        return new GhPullRequestDetail(pr.path("number").asInt(), reviewCount,
                new ChecksSummary(pass, fail, pending));
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new GhUnavailableException("gh exited " + exit + ": " + error.strip(), null);
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
