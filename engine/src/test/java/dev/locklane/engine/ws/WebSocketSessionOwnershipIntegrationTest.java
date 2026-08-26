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
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers #48's session-ownership done-when over a real network connection: a
 * session belongs to whoever first attaches to it, and a different authenticated
 * user is rejected rather than silently let in. Since #50, an unauthenticated
 * connection cannot reach ownership at all — that failure mode is covered here too,
 * since it is the other half of the same "who may attach" story.
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
        String aliceCookie = login("ws-owner-alice", "alice-password");
        String bobCookie = login("ws-owner-bob", "bob-password");

        RecordingHandler aliceHandler = new RecordingHandler();
        WebSocketSession aliceSession =
                AuthenticatedWebSocketClients.connect(aliceHandler, aliceCookie, uri(worktreeId, workDir));
        aliceSession.sendMessage(new TextMessage("0echo alices-output\n"));
        waitUntil(() -> aliceHandler.combined().contains("alices-output"), Duration.ofSeconds(5));

        RecordingHandler bobHandler = new RecordingHandler();
        WebSocketSession bobSession =
                AuthenticatedWebSocketClients.connect(bobHandler, bobCookie, uriWithoutDir(worktreeId));

        waitUntil(() -> !bobSession.isOpen(), Duration.ofSeconds(5));
        assertThat(bobHandler.closeStatus).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());

        aliceSession.close();
    }

    @Test
    void theOwningUserCanReattach(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-ownership-b";
        String aliceCookie = login("ws-owner-alice-2", "alice-password");

        RecordingHandler first = new RecordingHandler();
        WebSocketSession firstSession =
                AuthenticatedWebSocketClients.connect(first, aliceCookie, uri(worktreeId, workDir));
        firstSession.sendMessage(new TextMessage("0echo first-connection\n"));
        waitUntil(() -> first.combined().contains("first-connection"), Duration.ofSeconds(5));
        firstSession.close();
        waitUntil(() -> !firstSession.isOpen(), Duration.ofSeconds(5));

        RecordingHandler second = new RecordingHandler();
        WebSocketSession secondSession =
                AuthenticatedWebSocketClients.connect(second, aliceCookie, uriWithoutDir(worktreeId));
        waitUntil(secondSession::isOpen, Duration.ofSeconds(5));

        secondSession.close();
    }

    @Test
    void anUnauthenticatedAttachIsRejected(@TempDir Path workDir) {
        String worktreeId = "ws-ownership-anon-" + Instant.now().toEpochMilli();
        RecordingHandler anonHandler = new RecordingHandler();

        // No session cookie at all -- Spring Security (#50) must refuse the
        // handshake before it ever reaches TerminalWebSocketHandler, so the
        // client-side connect future fails rather than resolving to an open session.
        assertThatThrownBy(() ->
                new StandardWebSocketClient().execute(anonHandler, uri(worktreeId, workDir)).get())
                .isInstanceOf(ExecutionException.class);
    }

    private String login(String username, String password) throws Exception {
        return AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder, username, password);
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
