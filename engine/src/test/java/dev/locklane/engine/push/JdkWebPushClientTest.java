package dev.locklane.engine.push;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #860's request shape and how each push-service answer is classified, against a local stub server. */
class JdkWebPushClientTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(201);
    private final Map<String, String> seenHeaders = new ConcurrentHashMap<>();
    private byte[] seenBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) -> seenHeaders.put(name.toLowerCase(), values.get(0)));
            seenBody = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc");
    }

    @Test
    void postsTheEncryptedBodyWithTheWebPushHeaders() {
        WebPushClient.Delivery delivery = new JdkWebPushClient(HttpClient.newHttpClient())
                .send(endpoint(), new byte[] {1, 2, 3}, "vapid t=token, k=key");

        assertThat(delivery).isEqualTo(WebPushClient.Delivery.DELIVERED);
        assertThat(seenBody).containsExactly(1, 2, 3);
        assertThat(seenHeaders).containsEntry("content-encoding", "aes128gcm")
                .containsEntry("content-type", "application/octet-stream")
                .containsEntry("authorization", "vapid t=token, k=key")
                .containsEntry("urgency", "high")
                .containsKey("ttl");
    }

    @Test
    void aGoneSubscriptionIsReportedAsGone() {
        status.set(410);
        assertThat(new JdkWebPushClient(HttpClient.newHttpClient()).send(endpoint(), new byte[0], "vapid t=a, k=b"))
                .isEqualTo(WebPushClient.Delivery.GONE);
        status.set(404);
        assertThat(new JdkWebPushClient(HttpClient.newHttpClient()).send(endpoint(), new byte[0], "vapid t=a, k=b"))
                .isEqualTo(WebPushClient.Delivery.GONE);
    }

    @Test
    void anyOtherRefusalOrAnUnreachableServiceIsAFailureNotAnException() {
        status.set(500);
        assertThat(new JdkWebPushClient(HttpClient.newHttpClient()).send(endpoint(), new byte[0], "vapid t=a, k=b"))
                .isEqualTo(WebPushClient.Delivery.FAILED);
        server.stop(0);
        assertThat(new JdkWebPushClient(HttpClient.newHttpClient()).send(endpoint(), new byte[0], "vapid t=a, k=b"))
                .isEqualTo(WebPushClient.Delivery.FAILED);
    }
}
