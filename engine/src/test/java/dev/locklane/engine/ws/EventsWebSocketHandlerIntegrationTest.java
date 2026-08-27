package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #128's done-when over a real network connection (not in-process): a client
 * attaches to the app-wide {@code /ws/events} channel and an event published on
 * {@link EventBroadcaster} reaches it as JSON. No producer is wired to the
 * broadcaster yet (that is #129/#130) — this test stands in for one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventsWebSocketHandlerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EventBroadcaster eventBroadcaster;

    @Test
    void anEventPublishedOnTheBroadcasterReachesAConnectedClientAsJson() throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "events-handler-a", "password-a");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri());

        eventBroadcaster.broadcast("console.attention", Map.of("consoleId", "7-worktree"));

        waitUntil(() -> !client.messages.isEmpty(), Duration.ofSeconds(5));
        assertThat(client.messages).containsExactly(
                "{\"type\":\"console.attention\",\"consoleId\":\"7-worktree\"}");

        session.close();
    }

    @Test
    void aClientOnlyReceivesEventsWhileConnected() throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "events-handler-b", "password-b");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri());
        session.close();
        waitUntil(() -> !session.isOpen(), Duration.ofSeconds(5));

        // Must not throw even though the only subscriber just disconnected.
        eventBroadcaster.broadcast("no.subscribers.left");

        assertThat(client.messages).isEmpty();
    }

    private String uri() {
        return "ws://localhost:%d/ws/events".formatted(port);
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
    }
}
