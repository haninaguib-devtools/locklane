package dev.locklane.engine.push;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * The client's side of opting in to Web Push (#860): the engine's VAPID public key
 * (what the browser subscribes with), and registering or dropping the subscription
 * the browser got back. Gated as authenticated in {@code SecurityConfig}
 * ({@code /api/push/**}) and account-scoped below (ADR-105), the same shape as
 * {@code GhAccountsController}. The subscription body is the browser's own
 * {@code PushSubscription.toJSON()}: {@code {endpoint, keys: {p256dh, auth}}}.
 */
@RestController
@RequestMapping("/api/push")
public class PushSubscriptionController {

    private static final int AUTH_SECRET_BYTES = 16;

    private final VapidKeyPair keys;
    private final PushSubscriptionRepository repository;
    private final UserRepository userRepository;

    public PushSubscriptionController(VapidKeyPair keys, PushSubscriptionRepository repository,
            UserRepository userRepository) {
        this.keys = keys;
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @GetMapping("/vapid-public-key")
    public Map<String, String> vapidPublicKey() {
        return Map.of("publicKey", keys.publicKeyBase64Url());
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<?> subscribe(@RequestBody SubscriptionRequest request, Authentication authentication) {
        String problem = validate(request);
        if (problem != null) {
            return ResponseEntity.badRequest().body(Map.of("error", problem));
        }
        repository.save(currentUser(authentication).id(), request.endpoint(), request.keys().p256dh(),
                request.keys().auth(), Instant.now());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/subscriptions")
    public ResponseEntity<?> unsubscribe(@RequestBody UnsubscribeRequest request, Authentication authentication) {
        if (request == null || request.endpoint() == null || request.endpoint().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "endpoint is required"));
        }
        repository.delete(currentUser(authentication).id(), request.endpoint());
        return ResponseEntity.noContent().build();
    }

    /** The reason a subscription is refused, or {@code null} when it is well-formed. */
    static String validate(SubscriptionRequest request) {
        if (request == null || request.endpoint() == null || request.endpoint().isBlank()) {
            return "endpoint is required";
        }
        URI endpoint;
        try {
            endpoint = URI.create(request.endpoint());
        } catch (IllegalArgumentException e) {
            // silent: a malformed request is answered as 400 with this reason; the
            // exception carries nothing the caller isn't told.
            return "endpoint is not a URL";
        }
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null) {
            return "endpoint must be an https URL";
        }
        if (request.keys() == null || request.keys().p256dh() == null || request.keys().auth() == null) {
            return "keys.p256dh and keys.auth are required";
        }
        try {
            EcKeys.publicKeyFromRaw(EcKeys.fromBase64Url(request.keys().p256dh()));
        } catch (IllegalArgumentException e) {
            // silent: as above -- reported to the caller as 400.
            return "keys.p256dh is not a P-256 public key";
        }
        try {
            if (EcKeys.fromBase64Url(request.keys().auth()).length != AUTH_SECRET_BYTES) {
                return "keys.auth must be 16 bytes";
            }
        } catch (IllegalArgumentException e) {
            // silent: as above -- reported to the caller as 400.
            return "keys.auth is not base64url";
        }
        return null;
    }

    private UserRecord currentUser(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user has no account row"));
    }

    public record SubscriptionRequest(String endpoint, Keys keys) {
    }

    public record Keys(String p256dh, String auth) {
    }

    public record UnsubscribeRequest(String endpoint) {
    }
}
