package dev.locklane.engine.codeserver;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Opens a WebSocket from the engine to a console's loopback code-server (#655) — the
 * upstream half of {@link CodeServerWebSocketProxy}, behind an interface so the relay
 * can be exercised against a fake with no code-server and no network. The JDK's own
 * client ({@link JdkUpstreamWebSockets}) is the production implementation.
 */
public interface UpstreamWebSockets {

    /** What the proxy hears from the upstream socket; each call carries one frame. */
    interface Listener {
        void onText(CharSequence data, boolean last);

        void onBinary(ByteBuffer data, boolean last);

        void onClose(int code, String reason);

        void onError(Throwable error);
    }

    /** What the proxy can send to the upstream socket. Sends are blocking and sequential. */
    interface Upstream {
        void sendText(CharSequence data, boolean last);

        void sendBinary(ByteBuffer data, boolean last);

        void close(int code, String reason);

        void abort();
    }

    CompletableFuture<Upstream> connect(URI uri, Listener listener);
}
