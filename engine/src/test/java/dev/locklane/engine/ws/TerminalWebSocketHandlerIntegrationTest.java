package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.UserRepository;
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
 * Covers #7's done-when end to end, over a real network connection (not in-process):
 * a client attaches, exchanges terminal I/O, disconnects without killing the
 * session, and a new connection sees output produced while it was gone. Since #50,
 * the endpoint requires an authenticated session, so every connection here logs in
 * first via {@link AuthenticatedWebSocketClients} rather than connecting anonymously.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalWebSocketHandlerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void aClientCanAttachAndExchangeLiveTerminalIoOverTheNetwork(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-worktree-a";
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "ws-handler-a", "password-a");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri(worktreeId, workDir));

        session.sendMessage(new TextMessage("0echo hello-over-the-wire\n"));
        waitUntil(() -> client.combined().contains("hello-over-the-wire"), Duration.ofSeconds(5));

        session.close();
    }

    @Test
    void closingAConnectionDoesNotKillTheSessionAndAReattachSeesWhatWasMissed(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-worktree-b";
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "ws-handler-b", "password-b");

        RecordingHandler first = new RecordingHandler();
        WebSocketSession firstSession = AuthenticatedWebSocketClients.connect(first, cookie, uri(worktreeId, workDir));
        firstSession.sendMessage(new TextMessage("0echo produced-before-disconnect\n"));
        waitUntil(() -> first.combined().contains("produced-before-disconnect"), Duration.ofSeconds(5));

        firstSession.close(); // the client disconnecting — the session must survive this
        waitUntil(() -> !firstSession.isOpen(), Duration.ofSeconds(5));

        // No ?dir this time: the worktree is already known (in-memory, and in
        // SQLite via #6), so the reattach must resolve its working directory itself.
        RecordingHandler second = new RecordingHandler();
        WebSocketSession secondSession =
                AuthenticatedWebSocketClients.connect(second, cookie, uriWithoutDir(worktreeId));

        waitUntil(() -> second.combined().contains("produced-before-disconnect"), Duration.ofSeconds(5));

        secondSession.sendMessage(new TextMessage("0echo produced-after-reattach\n"));
        waitUntil(() -> second.combined().contains("produced-after-reattach"), Duration.ofSeconds(5));

        secondSession.close();
    }

    @Test
    void aNewSessionsPtyStartsAtTheRequestedSize(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-worktree-initial-size";
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "ws-handler-initial-size", "password-initial-size");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session =
                AuthenticatedWebSocketClients.connect(client, cookie, uriWithSize(worktreeId, workDir, 150, 45));

        session.sendMessage(new TextMessage("0stty size\n"));
        waitUntil(() -> client.combined().contains("45 150"), Duration.ofSeconds(5));

        session.close();
    }

    @Test
    void aClientCanResizeTheSessionsPtyAfterAttaching(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-worktree-resize";
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "ws-handler-resize", "password-resize");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri(worktreeId, workDir));

        session.sendMessage(new TextMessage("1120x40"));
        session.sendMessage(new TextMessage("0stty size\n"));
        waitUntil(() -> client.combined().contains("40 120"), Duration.ofSeconds(5));

        session.close();
    }

    @Test
    void aMalformedResizeMessageIsIgnoredRatherThanBreakingTheConnection(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-worktree-bad-resize";
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "ws-handler-bad-resize", "password-bad-resize");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri(worktreeId, workDir));

        session.sendMessage(new TextMessage("1not-a-size"));
        session.sendMessage(new TextMessage("0echo still-alive-after-bad-resize\n"));
        waitUntil(() -> client.combined().contains("still-alive-after-bad-resize"), Duration.ofSeconds(5));

        session.close();
    }

    private String uri(String worktreeId, Path workDir) {
        return "ws://localhost:%d/ws/sessions/%s?dir=%s".formatted(port, worktreeId, workDir);
    }

    private String uriWithSize(String worktreeId, Path workDir, int cols, int rows) {
        return "ws://localhost:%d/ws/sessions/%s?dir=%s&cols=%d&rows=%d".formatted(port, worktreeId, workDir, cols,
                rows);
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

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        String combined() {
            return String.join("", messages);
        }
    }
}
