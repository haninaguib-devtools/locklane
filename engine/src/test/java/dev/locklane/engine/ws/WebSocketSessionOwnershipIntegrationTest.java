package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.UserRecord;
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
 * Covers #242's done-when over a real network connection: a worktree session's
 * visibility and attach authorization derive from its owning project's
 * {@code owner_user_id} (ADR-101 Decision 6), not from whoever attaches first
 * (#48's old model, replaced here) — the project's owner may always (re)attach, and
 * an unrelated authenticated user is rejected whatever their role, an administrator
 * included (#394, ADR-105, which withdrew the exemption ADR-101 Decision 6 had
 * granted). Since #50, an unauthenticated connection cannot reach this logic at all —
 * that failure mode is covered here too, since it is the other half of the same
 * "who may attach" story.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSessionOwnershipIntegrationTest {

    // The engine's test data-dir is a fixed on-disk path shared across every test
    // run (see engine/src/test/resources/application.yml), not a fresh @TempDir --
    // so a literal username here could collide with a stale row (wrong role, wrong
    // id) left behind by an earlier run. #242's authorization reads a user's stored
    // role, unlike #48's old per-session ownership, so a stale collision here would
    // actually change a test's outcome rather than just being untidy; every
    // username in this class is suffixed with this run-unique id to rule that out.
    private static final long RUN_ID = Instant.now().toEpochMilli();

    @LocalServerPort
    int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void aNonOwnerNonAdminCannotAttachToAnotherUsersProjectSession(@TempDir Path workDir) throws Exception {
        String alice = "ws-owner-alice-" + RUN_ID;
        String bob = "ws-owner-bob-" + RUN_ID;
        String aliceCookie = login(alice, "alice-password");
        String bobCookie = login(bob, "bob-password");
        long projectId = projectOwnedBy(alice).id();
        String worktreeId = projectId + "-ownership-a";

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
        String alice = "ws-owner-alice-2-" + RUN_ID;
        String aliceCookie = login(alice, "alice-password");
        long projectId = projectOwnedBy(alice).id();
        String worktreeId = projectId + "-ownership-b";

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

    /**
     * The inverse of what #242 originally asserted here. Attaching is a live shell in
     * the project's checkout with its decrypted GitHub token in the environment, so
     * #394 (ADR-105) gives an administrator no more access to it than any other
     * unrelated account has: the handshake is refused with the same policy violation
     * bob gets above, and alice's own session is unaffected.
     */
    @Test
    void anAdminCannotAttachToAnotherUsersProjectSession(@TempDir Path workDir) throws Exception {
        String alice = "ws-owner-alice-3-" + RUN_ID;
        String admin = "ws-owner-admin-" + RUN_ID;
        String aliceCookie = login(alice, "alice-password");
        String adminCookie = login(admin, "admin-password", UserRecord.Role.ADMIN);
        long projectId = projectOwnedBy(alice).id();
        String worktreeId = projectId + "-ownership-c";

        RecordingHandler aliceHandler = new RecordingHandler();
        WebSocketSession aliceSession =
                AuthenticatedWebSocketClients.connect(aliceHandler, aliceCookie, uri(worktreeId, workDir));
        aliceSession.sendMessage(new TextMessage("0echo alices-output\n"));
        waitUntil(() -> aliceHandler.combined().contains("alices-output"), Duration.ofSeconds(5));

        RecordingHandler adminHandler = new RecordingHandler();
        WebSocketSession adminSession =
                AuthenticatedWebSocketClients.connect(adminHandler, adminCookie, uriWithoutDir(worktreeId));

        waitUntil(() -> !adminSession.isOpen(), Duration.ofSeconds(5));
        assertThat(adminHandler.closeStatus).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(adminHandler.combined()).doesNotContain("alices-output");

        aliceSession.close();
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

    private String login(String username, String password, UserRecord.Role role) throws Exception {
        return AuthenticatedWebSocketClients.loginAs(port, userRepository, passwordEncoder, username, password, role);
    }

    /** A real project row owned by {@code username}'s account — {@code username} must already have logged in once. */
    private ProjectRecord projectOwnedBy(String username) {
        long ownerId = userRepository.findByUsername(username).orElseThrow().id();
        return projectRepository.create("proj-" + username, "url", Path.of("/tmp/proj-" + username + "-" + ownerId),
                ownerId, Instant.now());
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
