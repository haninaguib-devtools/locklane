package dev.locklane.engine.push;

import java.net.URI;

/**
 * Delivers one already-encrypted message to a push service (#860). An interface so
 * {@link PushNotifier}'s tests can observe what would have been sent without any
 * network; {@link JdkWebPushClient} is the real one.
 */
public interface WebPushClient {

    /** What the push service said, reduced to what the caller acts on. */
    enum Delivery {
        /** Accepted (a 2xx). */
        DELIVERED,
        /** The subscription no longer exists at the service (404 or 410): forget it. */
        GONE,
        /** Anything else, transport failures included; logged by the client, kept by the caller. */
        FAILED
    }

    Delivery send(URI endpoint, byte[] encryptedBody, String authorizationHeader);
}
