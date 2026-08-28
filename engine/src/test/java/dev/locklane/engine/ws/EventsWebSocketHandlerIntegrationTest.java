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
    void connectingYieldsAnEngineVersionStampBeforeAnyOtherTraffic() throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "events-handler-d", "password-d");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri());

        waitUntil(() -> !client.messages.isEmpty(), Duration.ofSeconds(5));

        assertThat(client.messages.get(0)).matches("\\{\"type\":\"engineVersion\",\"version\":\".+\"}");

        session.close();
        // Leave the broadcaster's registry empty before finishing, so the other tests'
        // session-count waits only ever see their own session.
        waitUntil(() -> eventBroadcaster.registeredSessionCount() == 0, Duration.ofSeconds(5));
    }

    @Test
    void anEventPublishedOnTheBroadcasterReachesAConnectedClientAsJson() throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "events-handler-a", "password-a");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri());

        // The handshake completing here doesn't guarantee the server has already
        // registered this session with the broadcaster: registration is a side effect
        // on the server with no signal back to this client. Retrying the broadcast is
        // safe (it is a no-op while nobody is registered yet) and lets the wait resolve
        // on the real observable condition — the message actually landing. Waiting on
        // this exact message rather than "any message" matters now that every
        // connection also gets an unrelated engineVersion greeting (#273) up front.
        String expected = "{\"type\":\"console.attention\",\"consoleId\":\"7-worktree\"}";
        waitUntil(() -> {
            if (!client.messages.contains(expected)) {
                eventBroadcaster.broadcast("console.attention", Map.of("consoleId", "7-worktree"));
            }
            return client.messages.contains(expected);
        }, Duration.ofSeconds(5));

        session.close();
        // Leave the broadcaster's registry empty before finishing, so the other test's
        // session-count waits only ever see that test's own session.
        waitUntil(() -> eventBroadcaster.registeredSessionCount() == 0, Duration.ofSeconds(5));
    }

    @Test
    void aClientOnlyReceivesEventsWhileConnected() throws Exception {
        String cookie = AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder,
                "events-handler-b", "password-b");
        RecordingHandler client = new RecordingHandler();
        WebSocketSession session = AuthenticatedWebSocketClients.connect(client, cookie, uri());
        // The connection's own up-front greeting (engineVersion, #273) is sent before
        // the session is registered, but "sent" on the server and "received" on this
        // client are different events on different threads -- waiting on
        // registeredSessionCount() alone races the greeting's own network round trip
        // (it can flip to 1 before the client has actually processed the message).
        // Wait for the greeting to actually land first; only then is registration
        // guaranteed to have already happened too (register() runs strictly after the
        // send, in the same server-side thread), so the count check below is immediate.
        waitUntil(() -> !client.messages.isEmpty(), Duration.ofSeconds(5));
        waitUntil(() -> eventBroadcaster.registeredSessionCount() == 1, Duration.ofSeconds(5));
        int messagesBeforeClose = client.messages.size();

        session.close();
        // No client-observable callback proves the server has unregistered the session:
        // even the client's own afterConnectionClosed (what this test waited on before
        // #167) can fire once the container-level close handshake completes, before the
        // server's application-level afterConnectionClosed — the unregister — has
        // finished running. Wait on the broadcaster's own registry, the state that
        // actually gates delivery.
        waitUntil(() -> eventBroadcaster.registeredSessionCount() == 0, Duration.ofSeconds(5));

        // Must not throw even though the only subscriber just disconnected.
        eventBroadcaster.broadcast("no.subscribers.left");

        assertThat(client.messages).hasSize(messagesBeforeClose);
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
