package dev.locklane.engine.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The real {@link WebPushClient} (#860): one {@code POST} per message over the JDK's
 * own HTTP client, the same client every other outbound call in the engine already
 * uses. Headers per RFC 8030/8291/8292: the body is {@code aes128gcm}, the message
 * is urgent (an agent is waiting on the person), and it is kept for the person for
 * a few hours rather than a day -- an agent still waiting the next morning has
 * already shown up in the app by then.
 */
public class JdkWebPushClient implements WebPushClient {

    private static final Logger log = LoggerFactory.getLogger(JdkWebPushClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String TTL_SECONDS = String.valueOf(4 * 60 * 60);

    private final HttpClient httpClient;

    public JdkWebPushClient() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** Test-only: a client pointed at a local stub server. */
    JdkWebPushClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Delivery send(URI endpoint, byte[] encryptedBody, String authorizationHeader) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", TTL_SECONDS)
                .header("Urgency", "high")
                .header("Authorization", authorizationHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedBody))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Delivery.DELIVERED;
            }
            if (status == 404 || status == 410) {
                log.info("Push subscription at {} is gone ({}); forgetting it", endpoint.getHost(), status);
                return Delivery.GONE;
            }
            log.warn("Push service at {} refused a message: {} {}", endpoint.getHost(), status, abbreviate(response.body()));
            return Delivery.FAILED;
        } catch (IOException e) {
            log.warn("Could not reach the push service at {}", endpoint.getHost(), e);
            return Delivery.FAILED;
        } catch (InterruptedException e) {
            // silent: the interrupt flag is restored and the caller sees a failed
            // delivery -- nothing to diagnose in the exception itself.
            Thread.currentThread().interrupt();
            return Delivery.FAILED;
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
