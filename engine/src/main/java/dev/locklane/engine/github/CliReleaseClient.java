package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.process.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CliReleaseClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String repository;

    public CliReleaseClient(@Value("${locklane.release-check.repository}") String repository) {
        this.repository = repository;
    }

    @Override
    public Optional<GhRelease> latestRelease() {
        try {
            ProcessBuilder builder = new ProcessBuilder("gh", "release", "view",
                    "--repo", repository, "--json", "tagName,url");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            ProcessOutcome outcome = new ProcessOutcome(exit, output, error);
            if (outcome.failed()) {
                // No permanent release yet (only the rolling "latest" pre-release
                // exists) and a real gh failure look the same from here — either way
                // there is nothing to compare the running version against, and this
                // runs hourly, so DEBUG (not WARN) keeps a repo with no permanent
                // release yet from getting an hourly log line.
                log.debug("`gh release view` exited {}: {}", outcome.exitCode(), outcome.describe());
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(output);
            return Optional.of(new GhRelease(node.path("tagName").asText(), node.path("url").asText()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while running `gh release view`", e);
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Could not run gh to check for a newer release", e);
            return Optional.empty();
        }
    }
}
