package dev.locklane.engine.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
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
 * session, and a new connection sees output produced while it was gone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalWebSocketHandlerIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void aClientCanAttachAndExchangeLiveTerminalIoOverTheNetwork(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-worktree-a";
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(client, uri(worktreeId, workDir))
                .get();

        session.sendMessage(new TextMessage("echo hello-over-the-wire\n"));
        waitUntil(() -> client.combined().contains("hello-over-the-wire"), Duration.ofSeconds(5));

        session.close();
    }

    @Test
    void closingAConnectionDoesNotKillTheSessionAndAReattachSeesWhatWasMissed(@TempDir Path workDir) throws Exception {
        String worktreeId = "ws-worktree-b";
        StandardWebSocketClient wsClient = new StandardWebSocketClient();

        RecordingHandler first = new RecordingHandler();
        WebSocketSession firstSession = wsClient.execute(first, uri(worktreeId, workDir)).get();
        firstSession.sendMessage(new TextMessage("echo produced-before-disconnect\n"));
        waitUntil(() -> first.combined().contains("produced-before-disconnect"), Duration.ofSeconds(5));

        firstSession.close(); // the client disconnecting — the session must survive this
        waitUntil(() -> !firstSession.isOpen(), Duration.ofSeconds(5));

        // No ?dir this time: the worktree is already known (in-memory, and in
        // SQLite via #6), so the reattach must resolve its working directory itself.
        RecordingHandler second = new RecordingHandler();
        WebSocketSession secondSession = wsClient.execute(second, uriWithoutDir(worktreeId)).get();

        waitUntil(() -> second.combined().contains("produced-before-disconnect"), Duration.ofSeconds(5));

        secondSession.sendMessage(new TextMessage("echo produced-after-reattach\n"));
        waitUntil(() -> second.combined().contains("produced-after-reattach"), Duration.ofSeconds(5));

        secondSession.close();
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

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }

        String combined() {
            return String.join("", messages);
        }
    }
}
