package dev.locklane.engine.push;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;

/**
 * P-256 key handling for Web Push (#860): the raw encodings the protocol speaks --
 * a 65-byte uncompressed point ({@code 0x04 || x || y}) for a public key, a 32-byte
 * scalar for a private key, both base64url without padding on the wire -- converted
 * to and from the JDK's own key objects. JDK-only on purpose (ADR-114): nothing here
 * needs a provider beyond what Java 21 ships.
 */
final class EcKeys {

    private static final int COORDINATE_BYTES = 32;
    private static final int RAW_PUBLIC_KEY_BYTES = 1 + 2 * COORDINATE_BYTES;
    private static final byte UNCOMPRESSED_POINT = 0x04;
    private static final ECParameterSpec P256 = p256();

    private EcKeys() {
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is unavailable in this JVM", e);
        }
    }

    /** The 65-byte uncompressed point form of a public key. */
    static byte[] rawPublicKey(ECPublicKey key) {
        byte[] raw = new byte[RAW_PUBLIC_KEY_BYTES];
        raw[0] = UNCOMPRESSED_POINT;
        System.arraycopy(fixedLength(key.getW().getAffineX()), 0, raw, 1, COORDINATE_BYTES);
        System.arraycopy(fixedLength(key.getW().getAffineY()), 0, raw, 1 + COORDINATE_BYTES, COORDINATE_BYTES);
        return raw;
    }

    /**
     * A public key from its 65-byte uncompressed point form. Refuses anything that
     * is not a point on P-256 (RFC 8291 §5: a key off the curve can leak the other
     * party's private key), as an {@link IllegalArgumentException}.
     */
    static ECPublicKey publicKeyFromRaw(byte[] raw) {
        if (raw == null || raw.length != RAW_PUBLIC_KEY_BYTES || raw[0] != UNCOMPRESSED_POINT) {
            throw new IllegalArgumentException("not a 65-byte uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + COORDINATE_BYTES));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 1 + COORDINATE_BYTES, RAW_PUBLIC_KEY_BYTES));
        if (!onCurve(x, y)) {
            throw new IllegalArgumentException("point is not on P-256");
        }
        try {
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a valid P-256 public key", e);
        }
    }

    static ECPrivateKey privateKeyFromScalar(byte[] scalar) {
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a valid P-256 private key", e);
        }
    }

    /** The 32-byte scalar of a private key. */
    static byte[] scalar(ECPrivateKey key) {
        return fixedLength(key.getS());
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] fromBase64Url(String encoded) {
        return Base64.getUrlDecoder().decode(encoded);
    }

    private static byte[] fixedLength(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == COORDINATE_BYTES) {
            return bytes;
        }
        byte[] fixed = new byte[COORDINATE_BYTES];
        if (bytes.length > COORDINATE_BYTES) {
            // A leading sign byte from BigInteger's two's-complement encoding.
            System.arraycopy(bytes, bytes.length - COORDINATE_BYTES, fixed, 0, COORDINATE_BYTES);
        } else {
            System.arraycopy(bytes, 0, fixed, COORDINATE_BYTES - bytes.length, bytes.length);
        }
        return fixed;
    }

    private static boolean onCurve(BigInteger x, BigInteger y) {
        EllipticCurve curve = P256.getCurve();
        BigInteger p = ((java.security.spec.ECFieldFp) curve.getField()).getP();
        if (x.signum() < 0 || x.compareTo(p) >= 0 || y.signum() < 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.modPow(BigInteger.TWO, p);
        BigInteger right = x.modPow(BigInteger.valueOf(3), p).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return left.equals(right);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is unavailable in this JVM", e);
        }
    }
}
