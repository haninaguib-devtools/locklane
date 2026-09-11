package dev.locklane.engine.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #860's done-when for the VAPID header: a JWT the pair's own public key verifies, with RFC 8292's claims. */
class VapidSignerTest {

    @Test
    void producesAVapidHeaderWhoseTokenVerifiesWithThePublicKey() throws Exception {
        VapidKeyPair keys = new VapidKeyPair(EcKeys.generate());
        Instant now = Instant.parse("2026-09-10T10:00:00Z");

        String header = VapidSigner.authorizationHeader(keys,
                URI.create("https://fcm.googleapis.com/fcm/send/abc:def"), "mailto:someone@example.com", now);

        assertThat(header).startsWith("vapid t=").contains(", k=" + keys.publicKeyBase64Url());
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode joseHeader = new ObjectMapper().readTree(EcKeys.fromBase64Url(parts[0]));
        assertThat(joseHeader.path("alg").asText()).isEqualTo("ES256");
        assertThat(joseHeader.path("typ").asText()).isEqualTo("JWT");

        JsonNode claims = new ObjectMapper().readTree(EcKeys.fromBase64Url(parts[1]));
        assertThat(claims.path("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.path("sub").asText()).isEqualTo("mailto:someone@example.com");
        assertThat(claims.path("exp").asLong()).isEqualTo(now.plus(VapidSigner.TOKEN_LIFETIME).getEpochSecond());

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.publicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(EcKeys.fromBase64Url(parts[2]))).isTrue();
    }

    @Test
    void theAudienceKeepsAnExplicitPort() {
        assertThat(VapidSigner.origin(URI.create("https://push.example.net:8443/push/xyz")))
                .isEqualTo("https://push.example.net:8443");
        assertThat(VapidSigner.origin(URI.create("https://updates.push.services.mozilla.com/wpush/v2/abc")))
                .isEqualTo("https://updates.push.services.mozilla.com");
    }
}
