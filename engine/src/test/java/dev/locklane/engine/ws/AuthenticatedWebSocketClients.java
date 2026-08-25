package dev.locklane.engine.ws;

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

    /** Bootstraps a user directly (no signup endpoint exists), logs in, returns the session cookie header value. */
    static String loginAs(int port, UserRepository userRepository, PasswordEncoder passwordEncoder,
            String username, String password) throws Exception {
        if (userRepository.findByUsername(username).isEmpty()) {
            userRepository.create(username, passwordEncoder.encode(password), Instant.now());
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

    /** Connects with the given session cookie (from {@link #loginAs}) as a Cookie header. */
    static WebSocketSession connect(WebSocketHandler handler, String sessionCookie, String uri) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", sessionCookie);
        return new StandardWebSocketClient().execute(handler, headers, URI.create(uri)).get();
    }
}
