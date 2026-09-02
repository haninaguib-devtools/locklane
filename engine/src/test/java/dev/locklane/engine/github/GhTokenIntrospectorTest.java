package dev.locklane.engine.github;

import dev.locklane.engine.process.ProcessOutcome;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** #550: parsing of {@code gh api -i user}'s raw output — the real CLI is never run here. */
class GhTokenIntrospectorTest {

    private static final String RESPONSE = """
            HTTP/2.0 200 OK
            Content-Type: application/json; charset=utf-8
            X-Accepted-Oauth-Scopes:\s
            X-Oauth-Scopes: admin:public_key, gist, read:org, repo

            {"login":"someone","id":123}
            """;

    @Test
    void introspectReadsTheLoginAndScopes() {
        GhTokenIntrospector introspector = new GhTokenIntrospector(
                token -> Optional.of(new ProcessOutcome(0, RESPONSE, "")));

        Optional<GhTokenIntrospector.Introspection> result = introspector.introspect("ghp_whatever");

        assertThat(result).contains(new GhTokenIntrospector.Introspection("someone",
                Set.of("admin:public_key", "gist", "read:org", "repo")));
    }

    @Test
    void introspectIsEmptyWhenTheProcessFails() {
        GhTokenIntrospector introspector = new GhTokenIntrospector(
                token -> Optional.of(new ProcessOutcome(1, "", "bad credentials")));

        assertThat(introspector.introspect("bad-token")).isEmpty();
    }

    @Test
    void introspectIsEmptyWhenGhCannotBeRun() {
        GhTokenIntrospector introspector = new GhTokenIntrospector(token -> Optional.empty());

        assertThat(introspector.introspect("any-token")).isEmpty();
    }

    @Test
    void parseReadsLoginWithNoScopesHeader() {
        String response = "HTTP/2.0 200 OK\nContent-Type: application/json\n\n{\"login\":\"no-scopes\"}\n";

        Optional<GhTokenIntrospector.Introspection> result = GhTokenIntrospector.parse(response);

        assertThat(result).contains(new GhTokenIntrospector.Introspection("no-scopes", Set.of()));
    }

    @Test
    void parseIsEmptyWhenTheBodyHasNoLoginField() {
        String response = "HTTP/2.0 401 Unauthorized\nContent-Type: application/json\n\n{\"message\":\"Bad credentials\"}\n";

        assertThat(GhTokenIntrospector.parse(response)).isEmpty();
    }

    @Test
    void parseMatchesTheScopesHeaderCaseInsensitively() {
        String response = "HTTP/1.1 200 OK\r\nx-oauth-scopes: repo, workflow\r\n\r\n{\"login\":\"case-test\"}\r\n";

        assertThat(GhTokenIntrospector.parse(response))
                .contains(new GhTokenIntrospector.Introspection("case-test", Set.of("repo", "workflow")));
    }
}
