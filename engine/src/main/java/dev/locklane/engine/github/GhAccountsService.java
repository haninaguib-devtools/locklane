package dev.locklane.engine.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.process.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Lists the GitHub accounts {@code gh} is logged into on the engine host (#532), so
 * the Add Project dialog can let the operator choose which one a project acts as.
 * Read from {@code gh auth status --json hosts} on every call — an operator can
 * {@code gh auth login} another account while the engine runs, and the picker should
 * show it the next time it opens. Only the {@code github.com} host's accounts are
 * returned: the create path hard-wires {@code https://github.com/}, and
 * {@code gh auth token --user} defaults to that host.
 *
 * <p>Empty, never an error, when there are no accounts or {@code gh} is not
 * installed. With {@code --json}, gh 2.98.0 exits 0 even with no accounts at all
 * (printing {@code {"hosts":{}}}), so a missing binary is the only failure to absorb.
 */
@Service
public class GhAccountsService {

    private static final Logger log = LoggerFactory.getLogger(GhAccountsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long GH_TIMEOUT_SECONDS = 20;

    private final Supplier<Optional<String>> authStatusJson;

    @Autowired
    public GhAccountsService() {
        this(GhAccountsService::runGhAuthStatus);
    }

    /**
     * Test-only: substitutes the raw {@code gh auth status --json hosts} output —
     * empty standing in for "gh could not be run" — so the parsing is covered without
     * the real CLI.
     */
    GhAccountsService(Supplier<Optional<String>> authStatusJson) {
        this.authStatusJson = authStatusJson;
    }

    /** The {@code github.com} accounts in gh's own order; empty when there are none or gh is unavailable. */
    public List<GhAccount> accounts() {
        Optional<String> json = authStatusJson.get();
        if (json.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode hosts = MAPPER.readTree(json.get()).path("hosts").path("github.com");
            List<GhAccount> result = new ArrayList<>();
            for (JsonNode account : hosts) {
                String login = account.path("login").asText("");
                if (!login.isBlank()) {
                    result.add(new GhAccount(login, account.path("active").asBoolean(false)));
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            log.warn("Could not parse `gh auth status --json hosts` output", e);
            return List.of();
        }
    }

    /** Stdout of {@code gh auth status --json hosts}, or empty if gh could not be run to completion. */
    private static Optional<String> runGhAuthStatus() {
        try {
            Process process = new ProcessBuilder("gh", "auth", "status", "--json", "hosts").start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(GH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("`gh auth status` did not finish within {}s", GH_TIMEOUT_SECONDS);
                return Optional.empty();
            }
            ProcessOutcome outcome = new ProcessOutcome(process.exitValue(), out, err);
            if (outcome.failed()) {
                log.warn("`gh auth status --json hosts` exited {}: {}", outcome.exitCode(), outcome.describe());
                return Optional.empty();
            }
            return Optional.of(out);
        } catch (IOException e) {
            log.info("Could not run gh — is it installed and on PATH? Reporting no GitHub accounts.", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while running `gh auth status`", e);
            return Optional.empty();
        }
    }
}
