package dev.locklane.engine.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Message encryption for Web Push (#860): RFC 8291 over RFC 8188's {@code
 * aes128gcm} content encoding, on the JDK's own ECDH, HMAC-SHA256 and AES-GCM and
 * nothing else (ADR-114). One record per message: a notification payload is a few
 * hundred bytes, far below the 4096-byte record size the header declares.
 *
 * <p>The salt and the application server's ephemeral key pair are random per
 * message in {@link #encrypt(byte[], byte[], byte[])}; the package-visible overload
 * takes both, which is what lets {@code WebPushEncryptorTest} reproduce RFC 8291's
 * own worked example byte for byte -- the one check that proves this hand-rolled
 * derivation matches what every browser's push service expects.
 */
public final class WebPushEncryptor {

    private static final int SALT_BYTES = 16;
    private static final int RECORD_SIZE = 4096;
    private static final int CEK_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte PADDING_DELIMITER_LAST_RECORD = 0x02;
    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private WebPushEncryptor() {
    }

    /**
     * Encrypts {@code plaintext} for the browser whose subscription carries {@code
     * uaPublicKey} (its 65-byte {@code p256dh}) and {@code authSecret} (its 16-byte
     * {@code auth}); the result is the complete request body, header included.
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return encrypt(plaintext, uaPublicKey, authSecret, EcKeys.generate(), salt);
    }

    static byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret, KeyPair applicationServer, byte[] salt) {
        ECPublicKey uaPublic = EcKeys.publicKeyFromRaw(uaPublicKey);
        byte[] asPublicKey = EcKeys.rawPublicKey((ECPublicKey) applicationServer.getPublic());
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(applicationServer.getPrivate());
            agreement.doPhase(uaPublic, true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] keyInfo = concat(KEY_INFO_PREFIX, uaPublicKey, asPublicKey);
            byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
            byte[] cek = hkdf(salt, ikm, CEK_INFO, CEK_BYTES);
            byte[] nonce = hkdf(salt, ikm, NONCE_INFO, NONCE_BYTES);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[] {PADDING_DELIMITER_LAST_RECORD}));

            ByteBuffer header = ByteBuffer.allocate(SALT_BYTES + 4 + 1 + asPublicKey.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublicKey.length).put(asPublicKey);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push encryption failed", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869) extract-then-expand, for outputs of at most one hash block. */
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);
        byte[] expanded = hmac(prk, concat(info, new byte[] {0x01}));
        return Arrays.copyOf(expanded, length);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }
}
