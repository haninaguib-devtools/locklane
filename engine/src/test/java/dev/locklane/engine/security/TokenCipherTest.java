package dev.locklane.engine.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #81's done-when: a stored token is never plaintext. */
class TokenCipherTest {

    @Test
    void decryptReversesEncrypt(@TempDir Path dataDir) throws IOException {
        TokenCipher cipher = cipher(dataDir);

        String encrypted = cipher.encrypt("ghp_abc123");

        assertThat(cipher.decrypt(encrypted)).isEqualTo("ghp_abc123");
    }

    @Test
    void encryptedOutputIsNotThePlaintext(@TempDir Path dataDir) throws IOException {
        TokenCipher cipher = cipher(dataDir);

        String encrypted = cipher.encrypt("ghp_abc123");

        assertThat(encrypted).doesNotContain("ghp_abc123");
    }

    @Test
    void twoEncryptionsOfTheSameValueDiffer(@TempDir Path dataDir) throws IOException {
        // A random IV per call (#81) -- otherwise identical tokens would produce
        // identical ciphertext, leaking that two projects share a token.
        TokenCipher cipher = cipher(dataDir);

        assertThat(cipher.encrypt("ghp_abc123")).isNotEqualTo(cipher.encrypt("ghp_abc123"));
    }

    @Test
    void decryptsWithAFreshInstanceUsingTheSamePersistedKey(@TempDir Path dataDir) throws IOException {
        String encrypted = cipher(dataDir).encrypt("ghp_abc123");

        // A brand new TokenCipher/EncryptionKeyProvider, as if the process restarted.
        assertThat(cipher(dataDir).decrypt(encrypted)).isEqualTo("ghp_abc123");
    }

    private static TokenCipher cipher(Path dataDir) throws IOException {
        return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
    }
}
