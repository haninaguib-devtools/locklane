package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Fetches this repo's latest permanent release by running gh as a subprocess (#287),
 * the same technique {@link CliGhClient} uses for a managed project's own repo. Unlike
 * that per-project client, this one is a single engine-wide bean: the repo is passed
 * explicitly via {@code --repo} rather than relying on gh's cwd-based auto-detection,
 * since the packaged jar is not guaranteed to run inside a git checkout of this repo at
 * all, and it always runs as whatever identity {@code gh auth login} has on the host —
 * the same ambient-session fallback {@link CliGhClient} uses when a project has no
 * stored token, since this repo has none to store one against.
 */
@Component
public class CliReleaseClient implements ReleaseClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String repository;

    public CliReleaseClient(@Value("${locklane.release-check.repository}") String repository) {
        this.repository = repository;
    }

    @Override
    public Optional<GhRelease> latestRelease() {
        try {
            ProcessBuilder builder = new ProcessBuilder("gh", "release", "view",
                    "--repo", repository, "--json", "tagName");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                // No permanent release yet (only the rolling "latest" pre-release
                // exists) and a real gh failure look the same from here — either way
                // there is nothing to compare the running version against.
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(output);
            return Optional.of(new GhRelease(node.path("tagName").asText()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
