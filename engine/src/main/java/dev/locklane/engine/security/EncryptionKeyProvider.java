package dev.locklane.engine.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * The engine's own encryption key, for any data it encrypts at rest — deliberately
 * kept outside the SQLite database (in the same {@code locklane.data-dir}, alongside
 * {@code locklane.db}) so a copy of the database file alone does not expose it (#47).
 * Generated once, on first run; loaded from disk on every start after that.
 */
@Component
public class EncryptionKeyProvider {

    private static final int KEY_BYTES = 32;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final byte[] key;

    public EncryptionKeyProvider(@Value("${locklane.data-dir}") String dataDir) throws IOException {
        Path keyFile = Path.of(dataDir).resolve("key");
        this.key = Files.exists(keyFile) ? load(keyFile) : generate(keyFile);
    }

    public byte[] key() {
        return key.clone();
    }

    private static byte[] load(Path keyFile) throws IOException {
        return Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).strip());
    }

    private static byte[] generate(Path keyFile) throws IOException {
        Files.createDirectories(keyFile.getParent());
        byte[] generated = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated), StandardCharsets.UTF_8);
        restrictToOwner(keyFile);
        return generated;
    }

    /** No-op where the filesystem has no POSIX permission model (e.g. Windows). */
    private static void restrictToOwner(Path keyFile) {
        try {
            Files.setPosixFilePermissions(keyFile, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            // silent: best effort — not every filesystem supports POSIX permissions.
        }
    }
}
