package dev.locklane.engine.codeserver;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link UpstreamWebSockets} over {@code java.net.http.WebSocket} (#655). The
 * handshake carries an {@code Origin} equal to code-server's own loopback origin, for
 * the same reason {@link CodeServerHttpProxy} rewrites it: code-server refuses a
 * WebSocket whose {@code Origin} host is not its {@code Host}.
 */
@Component
public class JdkUpstreamWebSockets implements UpstreamWebSockets {

    /**
     * The close codes the JDK client will send (its own legality check); anything else
     * — 1005/1006 "no status"/"abnormal", the 1012–1015 range, a reserved value — is
     * relayed as a normal close rather than an exception.
     */
    private static final Set<Integer> SENDABLE_CLOSE_CODES = Set.of(1000, 1001, 1003, 1007, 1008, 1009, 1010, 1011);

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public CompletableFuture<Upstream> connect(URI uri, Listener listener) {
        WebSocket.Listener jdkListener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                listener.onText(data, last);
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                listener.onBinary(data, last);
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                listener.onClose(statusCode, reason);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                listener.onError(error);
            }
        };
        return client.newWebSocketBuilder()
                .header("Origin", "http://" + uri.getRawAuthority())
                .buildAsync(uri, jdkListener)
                .thenApply(JdkUpstream::new);
    }

    static int sendableCloseCode(int code) {
        return SENDABLE_CLOSE_CODES.contains(code) || (code >= 3000 && code <= 4999) ? code : 1000;
    }

    private record JdkUpstream(WebSocket webSocket) implements Upstream {
        @Override
        public void sendText(CharSequence data, boolean last) {
            webSocket.sendText(data, last).join();
        }

        @Override
        public void sendBinary(ByteBuffer data, boolean last) {
            webSocket.sendBinary(data, last).join();
        }

        @Override
        public void close(int code, String reason) {
            if (!webSocket.isOutputClosed()) {
                webSocket.sendClose(sendableCloseCode(code), reason == null ? "" : reason);
            }
        }

        @Override
        public void abort() {
            webSocket.abort();
        }
    }
}
