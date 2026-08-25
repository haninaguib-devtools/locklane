package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fetches issues by running {@code gh issue list} as a subprocess. */
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
