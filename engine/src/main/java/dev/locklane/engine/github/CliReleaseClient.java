package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.process.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
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
 *
 * <p>The process is run through {@link CliGhClient#runBounded} (#763): both streams
 * drained concurrently, and the call killed and given up on after the same timeout
 * the per-project client uses, so a stalled GitHub connection can never hold the
 * scheduler thread this check shares with the heartbeats.
 */
@Component
public class CliReleaseClient implements ReleaseClient {

    private static final Logger log = LoggerFactory.getLogger(CliReleaseClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String repository;
    private final String executable;
    private final Duration timeout;

    @Autowired
    public CliReleaseClient(@Value("${locklane.release-check.repository}") String repository) {
        this(repository, "gh", CliGhClient.DEFAULT_TIMEOUT);
    }

    /** Test-only: substitutes a fake executable for {@code gh} on PATH, and a shorter timeout. */
    CliReleaseClient(String repository, String executable, Duration timeout) {
        this.repository = repository;
        this.executable = executable;
        this.timeout = timeout;
    }

    @Override
    public Optional<GhRelease> latestRelease() {
        try {
            ProcessBuilder builder = new ProcessBuilder(executable, "release", "view",
                    "--repo", repository, "--json", "tagName,url");
            ProcessOutcome outcome = CliGhClient.runBounded(builder, timeout);
            if (outcome.failed()) {
                // No permanent release yet (only the rolling "latest" pre-release
                // exists) and a real gh failure look the same from here — either way
                // there is nothing to compare the running version against, and this
                // runs hourly, so DEBUG (not WARN) keeps a repo with no permanent
                // release yet from getting an hourly log line.
                log.debug("`gh release view` exited {}: {}", outcome.exitCode(), outcome.describe());
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(outcome.stdout());
            return Optional.of(new GhRelease(node.path("tagName").asText(), node.path("url").asText()));
        } catch (CliGhClient.ProcessTimedOut e) {
            // Unlike a nonzero exit, a hang is never "no release yet": it is worth a
            // WARN, once an hour at most.
            log.warn("Could not check for a newer release", e);
            return Optional.empty();
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
