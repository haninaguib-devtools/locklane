package dev.locklane.engine.github;

import dev.locklane.engine.process.ProcessOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the login and classic OAuth scopes a raw GitHub token carries — used to
 * validate a pasted token and to record what a device-flow token can do (#550), the
 * same {@code gh api -i user} shape {@code ProjectCheckoutService} already reads for
 * the {@code workflow}-scope gate (#531), duplicated rather than shared: that call
 * site only ever wants the scopes, never the login, and is deliberately left alone
 * by this task.
 */
@Component
public class GhTokenIntrospector {

    private static final Logger log = LoggerFactory.getLogger(GhTokenIntrospector.class);
    private static final Pattern LOGIN_FIELD = Pattern.compile("\"login\"\\s*:\\s*\"([^\"]*)\"");
    private static final long TIMEOUT_SECONDS = 20;

    private final Function<String, Optional<ProcessOutcome>> runner;

    public GhTokenIntrospector() {
        this(GhTokenIntrospector::runGhApiUser);
    }

    /** Test-only: substitutes the raw {@code gh api -i user} outcome for {@code token}. */
    GhTokenIntrospector(Function<String, Optional<ProcessOutcome>> runner) {
        this.runner = runner;
    }

    /** Empty when the token could not be validated at all — an unreachable host, an invalid token, no gh on PATH. */
    public Optional<Introspection> introspect(String token) {
        Optional<ProcessOutcome> outcome = runner.apply(token);
        if (outcome.isEmpty() || outcome.get().failed()) {
            return Optional.empty();
        }
        return parse(outcome.get().stdout());
    }

    static Optional<Introspection> parse(String rawResponse) {
        Optional<String> login = parseLogin(rawResponse);
        if (login.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Introspection(login.get(), parseScopes(rawResponse)));
    }

    /** The body's {@code "login"} field, from {@code gh api -i}'s status line + headers + blank line + JSON body shape. */
    private static Optional<String> parseLogin(String rawResponse) {
        int blankLine = rawResponse.indexOf("\n\n");
        String body = blankLine >= 0 ? rawResponse.substring(blankLine + 2) : rawResponse;
        Matcher matcher = LOGIN_FIELD.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Same {@code X-OAuth-Scopes} header parse as {@code ProjectCheckoutService#parseOauthScopes}. */
    private static Set<String> parseScopes(String rawResponse) {
        for (String line : rawResponse.split("\\r?\\n")) {
            if (line.isBlank()) {
                break; // end of the headers; the body never carries scopes
            }
            int colon = line.indexOf(':');
            if (colon < 0 || !line.substring(0, colon).strip().toLowerCase(Locale.ROOT).equals("x-oauth-scopes")) {
                continue;
            }
            Set<String> scopes = new LinkedHashSet<>();
            for (String scope : line.substring(colon + 1).split(",")) {
                if (!scope.isBlank()) {
                    scopes.add(scope.strip());
                }
            }
            return scopes;
        }
        return Set.of();
    }

    private static Optional<ProcessOutcome> runGhApiUser(String token) {
        try {
            ProcessBuilder builder = new ProcessBuilder("gh", "api", "-i", "user");
            builder.environment().put("GH_TOKEN", token);
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("`gh api -i user` did not finish within {}s", TIMEOUT_SECONDS);
                return Optional.empty();
            }
            return Optional.of(new ProcessOutcome(process.exitValue(), out, err));
        } catch (IOException e) {
            log.info("Could not run gh to introspect a GitHub token — is it installed and on PATH?", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while introspecting a GitHub token", e);
            return Optional.empty();
        }
    }

    /** A token's identity: the account it authenticates as, and the classic scopes it reports. */
    public record Introspection(String login, Set<String> scopes) {
    }
}
