package dev.locklane.engine.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code Authorization} header a push service checks a message against (#860,
 * RFC 8292): a JWT signed ES256 with the engine's {@link VapidKeyPair}, whose
 * audience is the push service's origin, plus the public key it verifies with.
 * The JDK's {@code SHA256withECDSAinP1363Format} yields the raw {@code r || s}
 * signature JOSE wants directly, so no DER re-encoding and no extra library
 * (ADR-114).
 */
final class VapidSigner {

    /** Well inside RFC 8292's 24-hour ceiling; a header is built fresh per message anyway. */
    static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEADER = EcKeys.base64Url("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private VapidSigner() {
    }

    /**
     * {@code vapid t=<jwt>, k=<public key>} for a message to {@code endpoint}, on
     * behalf of {@code contact} (a {@code mailto:} or {@code https:} URI the push
     * service may use to reach whoever runs this engine).
     */
    static String authorizationHeader(VapidKeyPair keys, URI endpoint, String contact, Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", origin(endpoint));
        claims.put("exp", now.plus(TOKEN_LIFETIME).getEpochSecond());
        claims.put("sub", contact);
        String signingInput;
        try {
            signingInput = HEADER + "." + EcKeys.base64Url(MAPPER.writeValueAsBytes(claims));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode VAPID claims", e);
        }
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(keys.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String jwt = signingInput + "." + EcKeys.base64Url(signature.sign());
            return "vapid t=" + jwt + ", k=" + keys.publicKeyBase64Url();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign the VAPID token", e);
        }
    }

    static String origin(URI endpoint) {
        StringBuilder origin = new StringBuilder(endpoint.getScheme()).append("://").append(endpoint.getHost());
        if (endpoint.getPort() != -1) {
            origin.append(':').append(endpoint.getPort());
        }
        return origin.toString();
    }
}
