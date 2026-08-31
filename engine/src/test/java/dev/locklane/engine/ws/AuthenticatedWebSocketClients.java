package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logs a test user in over real HTTP and opens WebSocket connections carrying that
 * session cookie — the WebSocket session endpoint requires authentication since #50,
 * so every network-level WS test needs this rather than connecting anonymously.
 * Public: shared across this package's several integration test classes.
 */
final class AuthenticatedWebSocketClients {

    private AuthenticatedWebSocketClients() {
    }

    /**
     * Bootstraps an ordinary (non-admin) user directly (no signup endpoint exists),
     * logs in, returns the session cookie header value.
     */
    static String loginAs(int port, UserRepository userRepository, PasswordEncoder passwordEncoder,
            String username, String password) throws Exception {
        return loginAs(port, userRepository, passwordEncoder, username, password, UserRecord.Role.USER);
    }

    /**
     * As above, with an explicit role — only for tests that are actually about a
     * role. It is no longer a way past session authorization: since #394 (ADR-105) an
     * administrator may attach to nothing an ordinary account could not, so a test
     * that needs an attach to succeed gives its user a real project via
     * {@link #projectOwnedBy} and uses that project's id as the session id's prefix.
     */
    static String loginAs(int port, UserRepository userRepository, PasswordEncoder passwordEncoder,
            String username, String password, UserRecord.Role role) throws Exception {
        if (userRepository.findByUsername(username).isEmpty()) {
            userRepository.create(username, passwordEncoder.encode(password), Instant.now(), role);
        }

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/auth/login".formatted(port)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=%s&password=%s".formatted(username, password)))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);

        return response.headers().firstValue("Set-Cookie")
                .map(cookie -> cookie.split(";", 2)[0])
                .orElseThrow(() -> new AssertionError("Login did not set a session cookie"));
    }

    /**
     * A project genuinely owned by {@code username}, so a session id prefixed with
     * its id passes {@code WorktreeSessionAuthorization} for that account (#394):
     * ownership is the whole of the check, with no role exemption to lean on instead.
     * A fresh row per call, named for the caller, keeps two tests' sessions apart.
     */
    static ProjectRecord projectOwnedBy(UserRepository userRepository, ProjectRepository projectRepository,
            String username) {
        long ownerId = userRepository.findByUsername(username).orElseThrow().id();
        return projectRepository.create("proj-" + username, "url",
                Path.of("/tmp/proj-" + username + "-" + Instant.now().toEpochMilli()), ownerId, Instant.now());
    }

    /** Connects with the given session cookie (from {@link #loginAs}) as a Cookie header. */
    static WebSocketSession connect(WebSocketHandler handler, String sessionCookie, String uri) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", sessionCookie);
        return new StandardWebSocketClient().execute(handler, headers, URI.create(uri)).get();
    }
}
