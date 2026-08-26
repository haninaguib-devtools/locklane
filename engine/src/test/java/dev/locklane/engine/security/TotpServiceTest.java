package dev.locklane.engine.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the code generator against the test vectors published in RFC 6238 (#88), not against
 * its own output — an implementation that agrees only with itself would still be an
 * implementation no authenticator app on earth agrees with.
 *
 * <p>The RFC's vectors are given for the ASCII secret {@code "12345678901234567890"} at fixed
 * instants; each expectation below is the SHA-1 column of the RFC's table, truncated to the
 * six digits this service produces.
 */
class TotpServiceTest {

    /** RFC 6238's SHA-1 test secret, in the Base32 form this service takes. */
    private static final String RFC_SECRET =
            TotpService.encodeBase32("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

    private final TotpService service = new TotpService();

    @Test
    void matchesTheRfc6238TestVectors() {
        // The RFC's table gives 8 digits; TOTP's lower digits are the same in either width, so
        // the 6-digit code is the last six of each published value.
        assertTrue(service.verify(RFC_SECRET, "287082", Instant.ofEpochSecond(59L)));
        assertTrue(service.verify(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L)));
        assertTrue(service.verify(RFC_SECRET, "050471", Instant.ofEpochSecond(1111111111L)));
        assertTrue(service.verify(RFC_SECRET, "005924", Instant.ofEpochSecond(1234567890L)));
        assertTrue(service.verify(RFC_SECRET, "279037", Instant.ofEpochSecond(2000000000L)));
    }

    @Test
    void acceptsOneStepOfClockSkewEitherWay() {
        // 1111111109 is a vector instant; ±30s is the neighbouring step in each direction, and
        // the code from that instant must still be accepted from either of them.
        assertTrue(service.verify(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L - 30)));
        assertTrue(service.verify(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L + 30)));
    }

    @Test
    void rejectsACodeTwoStepsAway() {
        assertFalse(service.verify(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L - 90)));
        assertFalse(service.verify(RFC_SECRET, "081804", Instant.ofEpochSecond(1111111109L + 90)));
    }

    @Test
    void rejectsWrongShapesRatherThanFailing() {
        Instant at = Instant.ofEpochSecond(1111111109L);
        assertFalse(service.verify(RFC_SECRET, null, at));
        assertFalse(service.verify(RFC_SECRET, "", at));
        assertFalse(service.verify(RFC_SECRET, "12345", at));
        assertFalse(service.verify(RFC_SECRET, "0818040", at));
        assertFalse(service.verify(RFC_SECRET, "abcdef", at));
        // A secret that is not Base32 at all is a false, not an exception escaping to a 500.
        assertFalse(service.verify("not!base32", "081804", at));
    }

    @Test
    void rejectsACodeFromADifferentSecret() {
        assertFalse(service.verify(service.generateSecret(), "081804", Instant.ofEpochSecond(1111111109L)));
    }

    @Test
    void generatesADistinct32CharacterBase32Secret() {
        String first = service.generateSecret();
        String second = service.generateSecret();
        assertEquals(32, first.length(), "20 random bytes encode to 32 Base32 characters");
        assertTrue(first.matches("[A-Z2-7]+"), "should be Base32 alphabet only: " + first);
        assertNotEquals(first, second, "each enrollment must get its own secret");
    }

    @Test
    void base32RoundTripsArbitraryBytes() {
        byte[] original = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertArrayEqualsAsBase32(original);
        assertArrayEqualsAsBase32(new byte[] {0});
        assertArrayEqualsAsBase32(new byte[] {(byte) 0xff, 0x00, 0x7f});
    }

    @Test
    void base32DecodingToleratesHowAHumanRetypesAManualKey() {
        byte[] original = new byte[] {1, 2, 3, 4, 5};
        String encoded = TotpService.encodeBase32(original);
        String asTyped = encoded.toLowerCase().substring(0, 4) + " " + encoded.substring(4);
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, TotpService.decodeBase32(asTyped));
    }

    @Test
    void provisioningUriCarriesWhatAnAuthenticatorNeeds() {
        String uri = service.provisioningUri("Locklane", "admin@example.com", "ABCDEFGHIJKLMNOP");
        assertTrue(uri.startsWith("otpauth://totp/Locklane:admin%40example.com?"), uri);
        assertTrue(uri.contains("secret=ABCDEFGHIJKLMNOP"), uri);
        assertTrue(uri.contains("issuer=Locklane"), uri);
        assertTrue(uri.contains("digits=6"), uri);
        assertTrue(uri.contains("period=30"), uri);
    }

    private static void assertArrayEqualsAsBase32(byte[] original) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                original, TotpService.decodeBase32(TotpService.encodeBase32(original)));
    }
}
