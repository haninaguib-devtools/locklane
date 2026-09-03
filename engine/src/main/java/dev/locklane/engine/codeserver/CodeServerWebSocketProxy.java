package dev.locklane.engine.codeserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Relays a browser's WebSocket under {@code /api/projects/{projectId}/consoles/{id}/ide/}
 * to that console's loopback code-server (#655) — the editor's whole RPC channel runs
 * over it. Mapped by {@link CodeServerProxyConfig} for upgrade requests only, on the
 * same {@code locklane.security.allowed-origins} list as the terminal socket, so any
 * origin that may attach a console may open its IDE and no other origin gains anything
 * new; authentication is enforced upstream in {@code SecurityConfig}, and the owner-only
 * check is {@link CodeServerProxyAuthorization}'s, made before any upstream connection
 * exists. A caller the check refuses is closed with {@code 1008} (policy violation),
 * the same closure the terminal socket uses for an unauthorized attach.
 *
 * <p>Frames are relayed as they arrive, partial ones included
 * ({@link #supportsPartialMessages}): a large editor message (a file save) is passed
 * fragment by fragment rather than tripping the container's per-message buffer, and
 * the {@code last} flag crosses over intact so the far side reassembles it exactly.
 * Either side closing closes the other with the same code.
 */
@Component
public class CodeServerWebSocketProxy extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CodeServerWebSocketProxy.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final CodeServerProxyAuthorization authorization;
    private final UpstreamWebSockets upstreams;
    private final Map<String, UpstreamWebSockets.Upstream> connected = new ConcurrentHashMap<>();

    public CodeServerWebSocketProxy(CodeServerProxyAuthorization authorization, UpstreamWebSockets upstreams) {
        this.authorization = authorization;
        this.upstreams = upstreams;
    }

    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI requested = session.getUri();
        Optional<IdeProxyPath> path = IdeProxyPath.parse(requested == null ? null : requested.getRawPath());
        Principal principal = session.getPrincipal();
        Optional<URI> upstream = path.flatMap(p -> authorization.upstreamFor(p, principal == null ? null : principal.getName()));
        if (upstream.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("You do not have access to this IDE"));
            return;
        }
        String rest = path.get().rest() == null ? "/" : path.get().rest();
        String query = requested.getRawQuery() == null ? "" : "?" + requested.getRawQuery();
        URI target = URI.create("ws://" + upstream.get().getRawAuthority() + rest + query);
        try {
            UpstreamWebSockets.Upstream connection = upstreams
                    .connect(target, new RelayToBrowser(session))
                    .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            connected.put(session.getId(), connection);
        } catch (Exception e) {
            log.warn("code-server WebSocket at {} unreachable", target, e);
            session.close(CloseStatus.SERVER_ERROR.withReason("The IDE is not reachable"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UpstreamWebSockets.Upstream upstream = connected.get(session.getId());
        if (upstream != null) {
            upstream.sendText(message.getPayload(), message.isLast());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UpstreamWebSockets.Upstream upstream = connected.get(session.getId());
        if (upstream != null) {
            upstream.sendBinary(message.getPayload(), message.isLast());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UpstreamWebSockets.Upstream upstream = connected.remove(session.getId());
        if (upstream != null) {
            upstream.close(status.getCode(), status.getReason());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        UpstreamWebSockets.Upstream upstream = connected.remove(session.getId());
        if (upstream != null) {
            upstream.abort();
        }
    }

    /** The upstream → browser direction; the browser → upstream one is the handler above. */
    private final class RelayToBrowser implements UpstreamWebSockets.Listener {

        private final WebSocketSession session;

        RelayToBrowser(WebSocketSession session) {
            this.session = session;
        }

        @Override
        public void onText(CharSequence data, boolean last) {
            send(new TextMessage(data, last));
        }

        @Override
        public void onBinary(ByteBuffer data, boolean last) {
            send(new BinaryMessage(data, last));
        }

        @Override
        public void onClose(int code, String reason) {
            UpstreamWebSockets.Upstream upstream = connected.remove(session.getId());
            closeBrowser(browserCloseStatus(code, reason));
            if (upstream != null) {
                upstream.abort();
            }
        }

        @Override
        public void onError(Throwable error) {
            log.debug("code-server WebSocket failed for {}", session.getId(), error);
            connected.remove(session.getId());
            closeBrowser(CloseStatus.SERVER_ERROR);
        }

        private void send(WebSocketMessage<?> message) {
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException | IllegalStateException e) {
                // The browser side went away mid-relay -- ordinary for a closed tab, so
                // debug only; the upstream is torn down to match.
                log.debug("Relaying to the browser side of {} failed; dropping its IDE socket", session.getId(), e);
                UpstreamWebSockets.Upstream upstream = connected.remove(session.getId());
                if (upstream != null) {
                    upstream.abort();
                }
            }
        }

        private void closeBrowser(CloseStatus status) {
            try {
                if (session.isOpen()) {
                    session.close(status);
                }
            } catch (IOException e) {
                log.debug("Closing the browser side of {} failed", session.getId(), e);
            }
        }
    }

    /** A close status the container will actually send: 1005/1006 carry no status by definition. */
    static CloseStatus browserCloseStatus(int code, String reason) {
        int sendable = code == 1005 || code == 1006 ? CloseStatus.NORMAL.getCode() : code;
        return reason == null || reason.isEmpty() ? new CloseStatus(sendable) : new CloseStatus(sendable, reason);
    }
}
