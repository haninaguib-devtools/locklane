package dev.locklane.engine.codeserver;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay itself (#655), with a fake upstream: what the browser sends reaches
 * code-server frame for frame, what code-server sends reaches the browser the same
 * way, and either side closing closes the other. Admission is decided before any
 * upstream connection exists.
 */
class CodeServerWebSocketProxyTest {

    private static final URI UPSTREAM = URI.create("http://127.0.0.1:41231");
    private static final Principal ALICE = () -> "alice";

    private final CodeServerProxyAuthorization authorization = mock(CodeServerProxyAuthorization.class);
    private final FakeUpstreams upstreams = new FakeUpstreams();
    private final CodeServerWebSocketProxy proxy = new CodeServerWebSocketProxy(authorization, upstreams);

    @Test
    void aCallerTheAuthorizationRefusesIsClosedWithPolicyViolationBeforeAnyUpstreamConnection() throws Exception {
        WebSocketSession session = session("s1", "/api/projects/1/consoles/1-174-x/ide/", null, ALICE);
        when(authorization.upstreamFor(any(), eq("alice"))).thenReturn(Optional.empty());

        proxy.afterConnectionEstablished(session);

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(upstreams.connectedTo).isNull();
    }

    @Test
    void connectsUpstreamAtTheSamePathAndQueryUnderTheConsolesLoopbackBase() throws Exception {
        WebSocketSession session = session("s1", "/api/projects/1/consoles/1-174-x/ide/stable-abc", "reconnectionToken=t1", ALICE);
        when(authorization.upstreamFor(new IdeProxyPath(1, "1-174-x", "/stable-abc"), "alice")).thenReturn(Optional.of(UPSTREAM));

        proxy.afterConnectionEstablished(session);

        assertThat(upstreams.connectedTo).isEqualTo(URI.create("ws://127.0.0.1:41231/stable-abc?reconnectionToken=t1"));
        verify(session, never()).close(any());
    }

    @Test
    void relaysFramesBothWaysWithTheirLastFlagIntact() throws Exception {
        WebSocketSession session = session("s1", "/api/projects/1/consoles/1-174-x/ide/", null, ALICE);
        when(authorization.upstreamFor(any(), eq("alice"))).thenReturn(Optional.of(UPSTREAM));
        proxy.afterConnectionEstablished(session);

        proxy.handleMessage(session, new TextMessage("hello", false));
        proxy.handleMessage(session, new BinaryMessage(bytes("part"), true));
        upstreams.listener.onBinary(bytes("from-upstream"), false);
        upstreams.listener.onText("done", true);

        assertThat(upstreams.upstream.sent).containsExactly("text:hello:last=false", "binary:part:last=true");
        ArgumentCaptor<WebSocketMessage<?>> toBrowser = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.times(2)).sendMessage(toBrowser.capture());
        List<WebSocketMessage<?>> messages = toBrowser.getAllValues();
        assertThat(messages.get(0)).isInstanceOf(BinaryMessage.class);
        assertThat(new String(((BinaryMessage) messages.get(0)).getPayload().array(), StandardCharsets.UTF_8)).isEqualTo("from-upstream");
        assertThat(messages.get(0).isLast()).isFalse();
        assertThat(messages.get(1)).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) messages.get(1)).getPayload()).isEqualTo("done");
        assertThat(messages.get(1).isLast()).isTrue();
    }

    @Test
    void theBrowserClosingClosesUpstreamWithTheSameCode() throws Exception {
        WebSocketSession session = session("s1", "/api/projects/1/consoles/1-174-x/ide/", null, ALICE);
        when(authorization.upstreamFor(any(), eq("alice"))).thenReturn(Optional.of(UPSTREAM));
        proxy.afterConnectionEstablished(session);

        proxy.afterConnectionClosed(session, CloseStatus.GOING_AWAY);

        assertThat(upstreams.upstream.closed).isEqualTo("1001:");
    }

    @Test
    void upstreamClosingClosesTheBrowserWithTheSameCodeAndNoStatusBecomesNormal() throws Exception {
        WebSocketSession session = session("s1", "/api/projects/1/consoles/1-174-x/ide/", null, ALICE);
        when(authorization.upstreamFor(any(), eq("alice"))).thenReturn(Optional.of(UPSTREAM));
        proxy.afterConnectionEstablished(session);

        upstreams.listener.onClose(1011, "boom");

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(1011);
        assertThat(status.getValue().getReason()).isEqualTo("boom");
        assertThat(CodeServerWebSocketProxy.browserCloseStatus(1006, null).getCode()).isEqualTo(1000);
        assertThat(CodeServerWebSocketProxy.browserCloseStatus(1005, "").getCode()).isEqualTo(1000);
    }

    @Test
    void anUnreachableUpstreamClosesTheBrowserWithServerError() throws Exception {
        WebSocketSession session = session("s1", "/api/projects/1/consoles/1-174-x/ide/", null, ALICE);
        when(authorization.upstreamFor(any(), eq("alice"))).thenReturn(Optional.of(UPSTREAM));
        upstreams.failWith = new java.net.ConnectException("refused");

        proxy.afterConnectionEstablished(session);

        ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
        verify(session).close(status.capture());
        assertThat(status.getValue().getCode()).isEqualTo(CloseStatus.SERVER_ERROR.getCode());
    }

    private static WebSocketSession session(String id, String path, String query, Principal principal) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(URI.create("ws://localhost:30000" + path + (query == null ? "" : "?" + query)));
        when(session.getPrincipal()).thenReturn(principal);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    static final class FakeUpstreams implements UpstreamWebSockets {
        URI connectedTo;
        Listener listener;
        FakeUpstream upstream;
        Exception failWith;

        @Override
        public CompletableFuture<Upstream> connect(URI uri, Listener listener) {
            if (failWith != null) {
                return CompletableFuture.failedFuture(failWith);
            }
            this.connectedTo = uri;
            this.listener = listener;
            this.upstream = new FakeUpstream();
            return CompletableFuture.completedFuture(upstream);
        }
    }

    static final class FakeUpstream implements UpstreamWebSockets.Upstream {
        final List<String> sent = new ArrayList<>();
        String closed;
        boolean aborted;

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
            aborted = true;
        }
    }
}
