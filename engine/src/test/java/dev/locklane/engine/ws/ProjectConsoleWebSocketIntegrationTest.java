package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.UserRepository;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Covers #139's done-when for the injected environment: attaching to a project
 * console's session id (`"<projectId>-console"`) gets the project's own decrypted
 * GitHub token as {@code GH_TOKEN} in the PTY's environment, and a project with no
 * stored token gets none — over a real network connection, same style as
 * {@link TerminalWebSocketHandlerIntegrationTest}. Each project is created as the
 * connecting account's own, so #242's authorization is satisfied by real ownership —
 * #394 (ADR-105) withdrew the administrator exemption these previously leaned on, and
 * ownership is what a console attach has always been meant to turn on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectConsoleWebSocketIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TokenCipher tokenCipher;

    @Test
    void aProjectConsoleSessionSeesTheProjectsDecryptedGithubToken(@TempDir Path workDir) throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "console-token-user", "password-console-token");
        long projectId = projectRepository.createReady("token-project", "url", workDir, "main",
                ownerId("console-token-user"), Instant.now()).id();
        projectRepository.setGithubToken(projectId, tokenCipher.encrypt("ghp_project139secret"));
        RecordingHandler client = new RecordingHandler();

        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, consoleUri(projectId, workDir));
        session.sendMessage(new TextMessage("0echo token-is-$GH_TOKEN\n"));
        waitUntil(() -> client.combined().contains("token-is-ghp_project139secret"), Duration.ofSeconds(5));

        session.close();
    }

    @Test
    void aProjectConsoleSessionWithNoStoredTokenGetsNoGhToken(@TempDir Path workDir) throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "console-no-token-user", "password-console-no-token");
        long projectId = projectRepository.createReady("no-token-project", "url", workDir, "main",
                ownerId("console-no-token-user"), Instant.now()).id();
        RecordingHandler client = new RecordingHandler();

        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, consoleUri(projectId, workDir));
        session.sendMessage(new TextMessage("0echo token-is-[$GH_TOKEN]\n"));
        waitUntil(() -> client.combined().contains("token-is-[]"), Duration.ofSeconds(5));

        session.close();
    }

    private long ownerId(String username) {
        return userRepository.findByUsername(username).orElseThrow().id();
    }

    private String consoleUri(long projectId, Path workDir) {
        return "ws://localhost:%d/ws/sessions/%d-console?dir=%s&cmd=shell".formatted(port, projectId, workDir);
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
