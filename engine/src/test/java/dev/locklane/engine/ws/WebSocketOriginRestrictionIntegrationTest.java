package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers #50's other done-when: {@code WebSocketConfig} no longer allows all
 * origins. `locklane.security.allowed-origins` in test resources' application.yml is
 * a single fixed origin ({@code http://allowed-test-origin.example}), so these tests
 * exercise both sides of that allowlist directly, independent of authentication
 * (which is covered separately in {@link WebSocketSessionOwnershipIntegrationTest}).
 * The allowed-origin case gives its user a real project and prefixes its worktree id
 * with that project's id, so #242's authorization is satisfied by actual ownership
 * rather than by the administrator exemption #394 (ADR-011) withdrew; the
 * disallowed-origin case never reaches that check at all (rejected earlier, at the
 * handshake).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketOriginRestrictionIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://allowed-test-origin.example";

    @LocalServerPort
    int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void aConnectionFromTheAllowedOriginSucceeds(@TempDir Path workDir) throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "ws-origin-allowed", "password");
        String worktreeId = AuthenticatedWebSocketClients
                .projectOwnedBy(userRepository, projectRepository, "ws-origin-allowed").id()
                + "-ws-origin-allowed";

        RecordingHandler handler = new RecordingHandler();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", cookie);
        headers.setOrigin(ALLOWED_ORIGIN);
        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, headers, URI.create(uri(worktreeId, workDir)))
                .get();

        session.sendMessage(new TextMessage("0echo from-allowed-origin\n"));
        waitUntil(() -> handler.combined().contains("from-allowed-origin"), Duration.ofSeconds(5));

        session.close();
    }

    @Test
    void aConnectionFromADisallowedOriginIsRejected(@TempDir Path workDir) throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "ws-origin-disallowed", "password");
        String worktreeId = "ws-origin-disallowed-" + Instant.now().toEpochMilli();

        RecordingHandler handler = new RecordingHandler();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", cookie);
        headers.setOrigin("http://not-in-the-allowlist.example");

        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(handler, headers, URI.create(uri(worktreeId, workDir)))
                .get())
                .isInstanceOf(ExecutionException.class);
    }

    private String uri(String worktreeId, Path workDir) {
        return "ws://localhost:%d/ws/sessions/%s?dir=%s".formatted(port, worktreeId, workDir);
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

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        String combined() {
            return String.join("", messages);
        }
    }
}
