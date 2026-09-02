package dev.locklane.engine.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-based one-time passwords (#88) — the six-digit codes an authenticator app such as
 * Google Authenticator or Authy shows and refreshes every thirty seconds.
 *
 * <p>The algorithm is RFC 6238 over RFC 4226: take the number of thirty-second steps since
 * the Unix epoch, HMAC-SHA1 it with the account's shared secret, and pull six decimal digits
 * out of the result by dynamic truncation. That is the whole of it, and it is written here
 * against the JDK's own {@code javax.crypto.Mac} rather than pulled from a library, so no
 * third-party code sits in the authentication path. {@code TotpServiceTest} checks it against
 * the test vectors published in RFC 6238 itself, not against this implementation's own output.
 *
 * <p>Secrets are handled as Base32 (RFC 4648, unpadded) because that is the alphabet
 * authenticator apps read and that {@code otpauth://} URIs carry. The JDK ships Base64 but
 * not Base32, so the codec is here too.
 *
 * <p>This class never touches the database and never sees an encrypted value — it is given a
 * Base32 secret and answers questions about it. Encrypting the secret at rest is
 * {@link TokenCipher}'s job, and storing it is the repository's.
 */
@Component
public class TotpService {

    /** RFC 6238's default, and what every mainstream authenticator app assumes. */
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    /**
     * How many steps either side of "now" a code is still accepted. One step in each
     * direction is the conventional allowance for the phone's clock drifting from the
     * server's; widening it mostly just lengthens how long a captured code stays usable.
     */
    private static final int SKEW_STEPS = 1;

    /**
     * 20 bytes — the HMAC-SHA1 block the RFC's key length recommendation lands on, and what
     * authenticator apps expect. Encodes to a 32-character Base32 key.
     */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random = new SecureRandom();

    /** A fresh random secret, Base32-encoded — the value the QR code and the manual key both carry. */
    public String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return encodeBase32(secret);
    }

    /**
     * The {@code otpauth://} URI an authenticator app reads out of a QR code. {@code issuer}
     * appears twice by convention — as the label prefix, which is what older apps display,
     * and as its own parameter, which is what newer ones use.
     */
    public String provisioningUri(String issuer, String accountName, String base32Secret) {
        String label = encode(issuer) + ":" + encode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    /**
     * Whether {@code code} is one this secret currently produces, allowing {@link #SKEW_STEPS}
     * of clock skew either way. A code that is not six digits — or not digits at all — is
     * simply false rather than an error: it is user input, and a wrong shape is a wrong code.
     */
    public boolean verify(String base32Secret, String code, Instant now) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        byte[] secret;
        try {
            secret = decodeBase32(base32Secret);
        } catch (IllegalArgumentException e) {
            // silent: a malformed stored secret verifies as "wrong code", the same
            // answer a mistyped one gets — this is user/config input, not a fault.
            return false;
        }
        long step = now.getEpochSecond() / STEP_SECONDS;
        boolean matched = false;
        for (long candidate = step - SKEW_STEPS; candidate <= step + SKEW_STEPS; candidate++) {
            // No early return: comparing every candidate regardless of an earlier match keeps
            // the work done independent of which step (if any) was the right one.
            matched |= constantTimeEquals(codeAt(secret, candidate), code);
        }
        return matched;
    }

    /**
     * The code this secret produces at a given instant. Nothing in the engine needs to
     * <em>issue</em> codes — that is the authenticator app's job, and this side only ever
     * verifies — so it stays package-private: it exists for the tests, which have to produce a
     * genuinely valid code to drive the enrollment endpoints end to end.
     */
    String currentCode(String base32Secret, Instant now) {
        return codeAt(decodeBase32(base32Secret), now.getEpochSecond() / STEP_SECONDS);
    }

    /** The code this secret produces for a given step counter — RFC 4226 §5.3. */
    private static String codeAt(byte[] secret, long counter) {
        byte[] counterBytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counterBytes[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }

        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            hash = mac.doFinal(counterBytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not compute a TOTP code", e);
        }

        // Dynamic truncation: the low nibble of the last byte picks where in the hash the
        // four bytes to use start; the top bit is masked off so the result is positive.
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);

        int modulus = (int) Math.pow(10, DIGITS);
        return String.format("%0" + DIGITS + "d", binary % modulus);
    }

    /** Length-independent comparison, so a wrong code leaks nothing about how wrong it was. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** RFC 4648 Base32, unpadded — what {@code otpauth://} URIs and manual-entry keys use. */
    static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                out.append(BASE32_ALPHABET.charAt((buffer >> bitsLeft) & 0x1f));
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    /**
     * The inverse. Tolerates the spacing and lowercase a human retyping a manual key produces,
     * and the {@code =} padding some encoders emit; anything else is not a Base32 secret.
     */
    static byte[] decodeBase32(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("Not a Base32 secret: null");
        }
        String normalized = encoded.replace(" ", "").replace("=", "").toUpperCase();
        byte[] out = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int written = 0;
        for (int i = 0; i < normalized.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(normalized.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not a Base32 secret: bad character");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[written++] = (byte) ((buffer >> bitsLeft) & 0xff);
            }
        }
        return out;
    }
}
