package dev.locklane.engine.push;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.List;
import java.util.Set;

/**
 * The engine's one VAPID key pair (#860, RFC 8292): the identity every push service
 * sees this engine as. Generated once, on first run, into {@code
 * <locklane.data-dir>/push-vapid.key} -- two lines, the private scalar then the
 * public point, both base64url -- and loaded from there on every start after that,
 * owner-only on disk exactly like {@link dev.locklane.engine.security.EncryptionKeyProvider}'s
 * file and for the same reason (ADR-114): a copy of the database alone must not be
 * enough to push to anyone's browser. A browser's subscription is bound to the
 * public key it was created with, so losing this file orphans every stored
 * subscription until each browser re-subscribes.
 */
@Component
public class VapidKeyPair {

    static final String FILE_NAME = "push-vapid.key";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;

    @Autowired
    public VapidKeyPair(@Value("${locklane.data-dir}") String dataDir) throws IOException {
        this(Path.of(dataDir).resolve(FILE_NAME));
    }

    VapidKeyPair(Path keyFile) throws IOException {
        KeyPair pair = Files.exists(keyFile) ? load(keyFile) : generate(keyFile);
        this.privateKey = (ECPrivateKey) pair.getPrivate();
        this.publicKey = (ECPublicKey) pair.getPublic();
    }

    /** Test-only: a pair that lives nowhere on disk. */
    VapidKeyPair(KeyPair pair) {
        this.privateKey = (ECPrivateKey) pair.getPrivate();
        this.publicKey = (ECPublicKey) pair.getPublic();
    }

    public ECPrivateKey privateKey() {
        return privateKey;
    }

    public ECPublicKey publicKey() {
        return publicKey;
    }

    /** The public key as the browser's {@code applicationServerKey} wants it: the raw point, base64url. */
    public String publicKeyBase64Url() {
        return EcKeys.base64Url(EcKeys.rawPublicKey(publicKey));
    }

    private static KeyPair load(Path keyFile) throws IOException {
        List<String> lines = Files.readAllLines(keyFile, StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.size() != 2) {
            throw new IOException(keyFile + " should hold two lines (private scalar, public point), found " + lines.size());
        }
        return new KeyPair(EcKeys.publicKeyFromRaw(EcKeys.fromBase64Url(lines.get(1))),
                EcKeys.privateKeyFromScalar(EcKeys.fromBase64Url(lines.get(0))));
    }

    private static KeyPair generate(Path keyFile) throws IOException {
        Files.createDirectories(keyFile.getParent());
        KeyPair pair = EcKeys.generate();
        String contents = EcKeys.base64Url(EcKeys.scalar((ECPrivateKey) pair.getPrivate())) + "\n"
                + EcKeys.base64Url(EcKeys.rawPublicKey((ECPublicKey) pair.getPublic())) + "\n";
        Files.writeString(keyFile, contents, StandardCharsets.UTF_8);
        restrictToOwner(keyFile);
        return pair;
    }

    /** No-op where the filesystem has no POSIX permission model (e.g. Windows). */
    private static void restrictToOwner(Path keyFile) {
        try {
            Files.setPosixFilePermissions(keyFile, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            // silent: best effort -- not every filesystem supports POSIX permissions.
        }
    }
}
