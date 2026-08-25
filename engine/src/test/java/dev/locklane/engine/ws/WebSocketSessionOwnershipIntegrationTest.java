package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #48's session-ownership done-when over a real network connection: a
 * session belongs to whoever first attaches to it, and a different authenticated
 * user is rejected rather than silently let in. The WebSocket endpoint itself does
 * not yet require authentication (#50) — an unauthenticated attach still works and
 * leaves the session unclaimed, covered separately below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSessionOwnershipIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void aSecondUserCannotAttachToTheFirstUsersSession(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-ownership-a";
        String aliceCookie = loginAs("ws-owner-alice", "alice-password");
        String bobCookie = loginAs("ws-owner-bob", "bob-password");

        RecordingHandler aliceHandler = new RecordingHandler();
        WebSocketSession aliceSession = connect(aliceHandler, aliceCookie, uri(worktreeId, workDir));
        aliceSession.sendMessage(new TextMessage("echo alices-output\n"));
        waitUntil(() -> aliceHandler.combined().contains("alices-output"), Duration.ofSeconds(5));

        RecordingHandler bobHandler = new RecordingHandler();
        WebSocketSession bobSession = connect(bobHandler, bobCookie, uriWithoutDir(worktreeId));

        waitUntil(() -> !bobSession.isOpen(), Duration.ofSeconds(5));
        assertThat(bobHandler.closeStatus).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());

        aliceSession.close();
    }

    @Test
    void theOwningUserCanReattach(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-ownership-b";
        String aliceCookie = loginAs("ws-owner-alice-2", "alice-password");

        RecordingHandler first = new RecordingHandler();
        WebSocketSession firstSession = connect(first, aliceCookie, uri(worktreeId, workDir));
        firstSession.sendMessage(new TextMessage("echo first-connection\n"));
        waitUntil(() -> first.combined().contains("first-connection"), Duration.ofSeconds(5));
        firstSession.close();
        waitUntil(() -> !firstSession.isOpen(), Duration.ofSeconds(5));

        RecordingHandler second = new RecordingHandler();
        WebSocketSession secondSession = connect(second, aliceCookie, uriWithoutDir(worktreeId));
        waitUntil(secondSession::isOpen, Duration.ofSeconds(5));

        secondSession.close();
    }

    @Test
    void anUnauthenticatedAttachStillWorksAndLeavesTheSessionUnclaimed(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-ownership-anon-" + Instant.now().toEpochMilli();

        RecordingHandler anonHandler = new RecordingHandler();
        WebSocketSession anonSession = new StandardWebSocketClient().execute(anonHandler, uri(worktreeId, workDir)).get();
        anonSession.sendMessage(new TextMessage("echo anon-output\n"));
        waitUntil(() -> anonHandler.combined().contains("anon-output"), Duration.ofSeconds(5));

        anonSession.close();
    }

    private WebSocketSession connect(RecordingHandler handler, String sessionCookie, String uri) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", sessionCookie);
        return new StandardWebSocketClient().execute(handler, headers, URI.create(uri)).get();
    }

    /** Bootstraps a user directly (no signup endpoint exists), logs in, returns the session cookie header value. */
    private String loginAs(String username, String password) throws Exception {
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

    private String uri(String worktreeId, Path workDir) {
        return "ws://localhost:%d/ws/sessions/%s?dir=%s".formatted(port, worktreeId, workDir);
    }

    private String uriWithoutDir(String worktreeId) {
        return "ws://localhost:%d/ws/sessions/%s".formatted(port, worktreeId);
    }

    private static void waitUntil(Supplier<Boolean> condition, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private static class RecordingHandler extends TextWebSocketHandler {
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile Integer closeStatus;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeStatus = status.getCode();
        }

        String combined() {
            return String.join("", messages);
        }
    }
}
