package dev.locklane.engine.codeserver;

import com.sun.net.httpserver.HttpServer;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.UserRepository;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.pty.SessionRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #655's done-when over the real dispatch, security and WebSocket stack: the IDE is
 * reachable at the engine's own proxied path, an anonymous request gets 401, a
 * non-owner gets 404, an IDE nobody started is 404, plain HTTP is forwarded to the
 * console's loopback code-server with the outer proxy's headers stripped and
 * {@code Origin} rewritten, and an {@code Upgrade: websocket} on the same path family
 * reaches the WebSocket proxy (not the HTTP one) and relays. "code-server" here is a
 * {@link HttpServer} started on exactly the port the service told the (fake) process
 * to bind, plus a fake upstream-WebSocket connector bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CodeServerIdeProxyIntegrationTest {

    private static final long RUN_ID = Instant.now().toEpochMilli();
    private static final List<HttpServer> FAKE_CODE_SERVERS = new CopyOnWriteArrayList<>();
    private static volatile Map<String, List<String>> lastUpstreamHeaders = Map.of();
    private static volatile String lastUpstreamRequestLine = "";

    @LocalServerPort
    int port;

    @Autowired
    UserRepository userRepository;
    @Autowired
    ProjectRepository projectRepository;
    @Autowired
    WorktreeSessionRepository worktreeSessionRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    FakeUpstreams fakeUpstreams;

    @TestConfiguration
    static class FakeCodeServer {

        /**
         * Spawns nothing: on the port the service chose, a plain HTTP server stands in
         * for code-server and records what it was asked, so the test can see exactly
         * what crossed the proxy.
         */
        @Bean
        @Primary
        CodeServerService fakeCodeServerService(SessionRegistry sessionRegistry) {
            return new CodeServerService(sessionRegistry, Path.of("/unused/code-server"), command -> {
                int bindPort = Integer.parseInt(List.of(command).get(2).substring("127.0.0.1:".length()));
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", bindPort), 0);
                server.createContext("/", exchange -> {
                    lastUpstreamHeaders = exchange.getRequestHeaders();
                    lastUpstreamRequestLine = exchange.getRequestMethod() + " " + exchange.getRequestURI();
                    byte[] requestBody;
                    try (InputStream in = exchange.getRequestBody()) {
                        requestBody = in.readAllBytes();
                    }
                    byte[] body = ("fake code-server saw " + lastUpstreamRequestLine
                            + (requestBody.length == 0 ? "" : " body=" + new String(requestBody, StandardCharsets.UTF_8)))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                    exchange.getResponseHeaders().add("X-Fake-Code-Server", "yes");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
                server.start();
                FAKE_CODE_SERVERS.add(server);
                return new ProcessBuilder("sleep", "60").start();
            });
        }

        @Bean
        @Primary
        FakeUpstreams fakeUpstreams() {
            return new FakeUpstreams();
        }
    }

    @AfterAll
    static void stopFakeCodeServers() {
        FAKE_CODE_SERVERS.forEach(server -> server.stop(0));
    }

    @Test
    void theOwnerReachesTheIdeAtTheProxiedPathAndNobodyElseDoes(@TempDir Path worktree) throws Exception {
        String alice = "ide-alice-" + RUN_ID;
        String bob = "ide-bob-" + RUN_ID;
        String aliceCookie = login(alice, "alice-password");
        String bobCookie = login(bob, "bob-password");
        long projectId = projectOwnedBy(alice);
        // Real console ids are "<project>-<issue>-..." or "<project>-console-..." --
        // the shapes IssueWorktreeService's listing recognizes as the project's own.
        String consoleId = projectId + "-174-ide-owned";
        String neverOpened = projectId + "-console-never-opened";
        worktreeSessionRepository.recordAttach(consoleId, worktree, Instant.now(), alice);
        worktreeSessionRepository.recordAttach(neverOpened, worktree, Instant.now(), alice);

        HttpResponse<String> opened = send(post(url("/api/projects/" + projectId + "/consoles/" + consoleId + "/open-ide"), aliceCookie));
        assertThat(opened.statusCode()).isEqualTo(200);
        String idePath = "/api/projects/" + projectId + "/consoles/" + consoleId + "/ide/";
        assertThat(opened.body()).isEqualTo("{\"url\":\"" + idePath + "\"}");

        // Anonymous: 401 from the security entry point, never IDE content.
        assertThat(send(get(url(idePath), null)).statusCode()).isEqualTo(401);
        // Another account: 404, indistinguishable from a console that does not exist.
        assertThat(send(get(url(idePath), bobCookie)).statusCode()).isEqualTo(404);
        // The owner, but an IDE nobody started: 404, and nothing got started by asking.
        assertThat(send(get(url("/api/projects/" + projectId + "/consoles/" + neverOpened + "/ide/"), aliceCookie)).statusCode())
                .isEqualTo(404);

        HttpResponse<String> root = send(get(url(idePath), aliceCookie));
        assertThat(root.statusCode()).as(root.body()).isEqualTo(200);
        assertThat(root.body()).isEqualTo("fake code-server saw GET /");
        assertThat(root.headers().firstValue("X-Fake-Code-Server")).contains("yes");
        // Tomcat re-serializes the media type it forwards (dropping the space), so
        // only the type and charset are pinned, not the exact spelling.
        assertThat(root.headers().firstValue("Content-Type").orElseThrow().replace(" ", ""))
                .isEqualTo("text/plain;charset=utf-8");
    }

    @Test
    void forwardsPathQueryMethodAndBodyWhileRewritingOriginAndDroppingTheOuterProxysHeaders(@TempDir Path worktree)
            throws Exception {
        String alice = "ide-headers-alice-" + RUN_ID;
        String aliceCookie = login(alice, "alice-password");
        long projectId = projectOwnedBy(alice);
        String consoleId = projectId + "-175-ide-headers";
        worktreeSessionRepository.recordAttach(consoleId, worktree, Instant.now(), alice);
        send(post(url("/api/projects/" + projectId + "/consoles/" + consoleId + "/open-ide"), aliceCookie));
        String idePath = "/api/projects/" + projectId + "/consoles/" + consoleId + "/ide";

        HttpResponse<String> asset = send(HttpRequest.newBuilder(URI.create(url(idePath + "/static/out/vs/a%20b.js?v=1&x=y")))
                .header("Cookie", aliceCookie)
                .header("Origin", "https://locklane.example")
                .header("X-Forwarded-Host", "locklane.example")
                .header("X-Forwarded-Proto", "https")
                .header("Accept", "text/javascript")
                .POST(HttpRequest.BodyPublishers.ofString("payload"))
                .build());

        assertThat(asset.statusCode()).as(asset.body()).isEqualTo(200);
        assertThat(asset.body()).isEqualTo("fake code-server saw POST /static/out/vs/a%20b.js?v=1&x=y body=payload");
        assertThat(lastUpstreamHeaders.get("Origin")).containsExactly("http://127.0.0.1:" + upstreamPort());
        assertThat(lastUpstreamHeaders.get("Accept")).containsExactly("text/javascript");
        assertThat(lastUpstreamHeaders).doesNotContainKeys("X-forwarded-host", "X-forwarded-proto", "Cookie-not-a-header");
        assertThat(lastUpstreamHeaders.get("Host")).containsExactly("127.0.0.1:" + upstreamPort());

        // ".../ide" with no slash: a relative redirect onto the slash-terminated form,
        // query preserved, so code-server's relative links resolve inside the prefix.
        HttpResponse<String> bare = send(get(url(idePath + "?folder=x"), aliceCookie));
        assertThat(bare.statusCode()).isEqualTo(302);
        assertThat(bare.headers().firstValue("Location")).contains(idePath + "/?folder=x");
    }

    @Test
    void anUpgradeOnTheSamePathReachesTheWebSocketProxyAndRelaysForTheOwnerOnly(@TempDir Path worktree) throws Exception {
        String alice = "ide-ws-alice-" + RUN_ID;
        String bob = "ide-ws-bob-" + RUN_ID;
        String aliceCookie = login(alice, "alice-password");
        String bobCookie = login(bob, "bob-password");
        long projectId = projectOwnedBy(alice);
        String consoleId = projectId + "-176-ide-ws";
        worktreeSessionRepository.recordAttach(consoleId, worktree, Instant.now(), alice);
        send(post(url("/api/projects/" + projectId + "/consoles/" + consoleId + "/open-ide"), aliceCookie));
        String wsUrl = "ws://localhost:" + port + "/api/projects/" + projectId + "/consoles/" + consoleId
                + "/ide/stable-abc?reconnectionToken=t1";

        RecordingHandler bobHandler = new RecordingHandler();
        connect(bobHandler, bobCookie, wsUrl);
        waitUntil(() -> bobHandler.closeStatus != null, Duration.ofSeconds(5));
        assertThat(bobHandler.closeStatus).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(fakeUpstreams.connectedTo).isNull();

        RecordingHandler aliceHandler = new RecordingHandler();
        WebSocketSession aliceSession = connect(aliceHandler, aliceCookie, wsUrl);
        waitUntil(() -> fakeUpstreams.connectedTo != null, Duration.ofSeconds(5));
        assertThat(fakeUpstreams.connectedTo)
                .isEqualTo(URI.create("ws://127.0.0.1:" + upstreamPort() + "/stable-abc?reconnectionToken=t1"));

        aliceSession.sendMessage(new BinaryMessage("from-browser".getBytes(StandardCharsets.UTF_8)));
        waitUntil(() -> !fakeUpstreams.upstream.sent.isEmpty(), Duration.ofSeconds(5));
        assertThat(fakeUpstreams.upstream.sent).containsExactly("binary:from-browser:last=true");

        fakeUpstreams.listener.onText("from-code-server", true);
        waitUntil(() -> aliceHandler.combined().contains("from-code-server"), Duration.ofSeconds(5));

        aliceSession.close(CloseStatus.NORMAL);
        waitUntil(() -> fakeUpstreams.upstream.closed != null, Duration.ofSeconds(5));
        assertThat(fakeUpstreams.upstream.closed).startsWith("1000");
    }

    private static int upstreamPort() {
        return FAKE_CODE_SERVERS.get(FAKE_CODE_SERVERS.size() - 1).getAddress().getPort();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpRequest get(String url, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return builder.build();
    }

    private static HttpRequest post(String url, String cookie) {
        return HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** As {@code AuthenticatedWebSocketClients.loginAs} (package-private to {@code ws}): a real login, its cookie returned. */
    private String login(String username, String password) throws Exception {
        if (userRepository.findByUsername(username).isEmpty()) {
            userRepository.create(username, passwordEncoder.encode(password), Instant.now());
        }
        HttpResponse<Void> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=%s&password=%s".formatted(username, password)))
                .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("Set-Cookie").map(cookie -> cookie.split(";", 2)[0]).orElseThrow();
    }

    private long projectOwnedBy(String username) {
        long ownerId = userRepository.findByUsername(username).orElseThrow().id();
        return projectRepository.create("proj-" + username, "url",
                Path.of("/tmp/proj-" + username + "-" + RUN_ID), ownerId, Instant.now()).id();
    }

    private static WebSocketSession connect(AbstractWebSocketHandler handler, String cookie, String uri) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", cookie);
        return new StandardWebSocketClient().execute(handler, headers, URI.create(uri)).get();
    }

    private static void waitUntil(Supplier<Boolean> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(20);
        }
    }

    private static final class RecordingHandler extends AbstractWebSocketHandler {
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

    /** The bean standing in for {@code JdkUpstreamWebSockets}; records what the proxy asked of it. */
    static final class FakeUpstreams implements UpstreamWebSockets {
        volatile URI connectedTo;
        volatile Listener listener;
        volatile FakeUpstream upstream;

        @Override
        public CompletableFuture<Upstream> connect(URI uri, Listener listener) {
            this.connectedTo = uri;
            this.listener = listener;
            this.upstream = new FakeUpstream();
            return CompletableFuture.completedFuture(upstream);
        }
    }

    static final class FakeUpstream implements UpstreamWebSockets.Upstream {
        final List<String> sent = new CopyOnWriteArrayList<>();
        volatile String closed;

        @Override
        public void sendText(CharSequence data, boolean last) {
            sent.add("text:" + data + ":last=" + last);
        }

        @Override
        public void sendBinary(ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sent.add("binary:" + new String(bytes, StandardCharsets.UTF_8) + ":last=" + last);
        }

        @Override
        public void close(int code, String reason) {
            closed = code + ":" + (reason == null ? "" : reason);
        }

        @Override
        public void abort() {
            closed = "aborted";
        }
    }
}
