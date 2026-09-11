package dev.locklane.engine.push;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers #860's done-when for the hand-rolled encryption: RFC 8291's own worked
 * example (§5 and Appendix A), reproduced byte for byte from the same keys, salt
 * and plaintext. Every intermediate step -- ECDH, the two HKDF stages, AES-128-GCM,
 * the aes128gcm header -- has to be right for the last byte to match.
 */
class WebPushEncryptorTest {

    private static final byte[] PLAINTEXT = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII);
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String EXPECTED_BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
            + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
            + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void reproducesRfc8291sWorkedExampleByteForByte() {
        KeyPair applicationServer = new KeyPair(EcKeys.publicKeyFromRaw(EcKeys.fromBase64Url(AS_PUBLIC)),
                EcKeys.privateKeyFromScalar(EcKeys.fromBase64Url(AS_PRIVATE)));

        byte[] body = WebPushEncryptor.encrypt(PLAINTEXT, EcKeys.fromBase64Url(UA_PUBLIC),
                EcKeys.fromBase64Url(AUTH_SECRET), applicationServer, EcKeys.fromBase64Url(SALT));

        assertThat(EcKeys.base64Url(body)).isEqualTo(EXPECTED_BODY);
        // 86-byte header + the 41-byte plaintext + its delimiter + the 16-byte tag. The
        // RFC's own "Content-Length: 145" is off by one (its plaintext is 41 bytes, not
        // 42); the encoded body it prints, which this reproduces, is 144.
        assertThat(body).hasSize(144);
    }

    @Test
    void theKeyEncodingsRoundTripThroughTheJdkObjects() {
        assertThat(EcKeys.base64Url(EcKeys.rawPublicKey(EcKeys.publicKeyFromRaw(EcKeys.fromBase64Url(UA_PUBLIC)))))
                .isEqualTo(UA_PUBLIC);
        assertThat(EcKeys.base64Url(EcKeys.scalar(EcKeys.privateKeyFromScalar(EcKeys.fromBase64Url(UA_PRIVATE)))))
                .isEqualTo(UA_PRIVATE);
    }

    @Test
    void aFreshMessageUsesARandomSaltAndKeyAndDeclaresTheHeaderRfc8188Expects() {
        byte[] uaPublic = EcKeys.fromBase64Url(UA_PUBLIC);
        byte[] auth = EcKeys.fromBase64Url(AUTH_SECRET);

        byte[] first = WebPushEncryptor.encrypt(PLAINTEXT, uaPublic, auth);
        byte[] second = WebPushEncryptor.encrypt(PLAINTEXT, uaPublic, auth);

        assertThat(first).hasSize(16 + 4 + 1 + 65 + PLAINTEXT.length + 1 + 16);
        assertThat(Arrays.copyOf(first, 16)).isNotEqualTo(Arrays.copyOf(second, 16));
        ByteBuffer header = ByteBuffer.wrap(first, 16, 5);
        assertThat(header.getInt()).isEqualTo(4096);
        assertThat(header.get()).isEqualTo((byte) 65);
        assertThat(first[21]).isEqualTo((byte) 0x04);
        assertThat(Arrays.copyOfRange(first, 21, 86)).isNotEqualTo(Arrays.copyOfRange(second, 21, 86));
    }

    @Test
    void aPublicKeyOffTheCurveIsRefused() {
        byte[] tampered = EcKeys.fromBase64Url(UA_PUBLIC);
        tampered[64] ^= 0x01;

        assertThatThrownBy(() -> EcKeys.publicKeyFromRaw(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not on P-256");
        assertThatThrownBy(() -> EcKeys.publicKeyFromRaw(new byte[] {0x04, 0x01}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
