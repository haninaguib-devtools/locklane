package dev.locklane.engine.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #47's done-when for the encryption key file. */
class EncryptionKeyProviderTest {

    @Test
    void generatesAKeyFileOnFirstRun(@TempDir Path dataDir) throws IOException {
        new EncryptionKeyProvider(dataDir.toString());

        assertThat(dataDir.resolve("key")).exists();
    }

    @Test
    void loadsTheSameKeyOnASubsequentStart(@TempDir Path dataDir) throws IOException {
        byte[] firstRun = new EncryptionKeyProvider(dataDir.toString()).key();
        byte[] secondRun = new EncryptionKeyProvider(dataDir.toString()).key();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    @Test
    void theKeyFileIsRestrictedToItsOwnerWherePosixPermissionsAreSupported(@TempDir Path dataDir) throws IOException {
        new EncryptionKeyProvider(dataDir.toString());
        Path keyFile = dataDir.resolve("key");

        PosixFileAttributeView posixView = Files.getFileAttributeView(keyFile, PosixFileAttributeView.class);
        if (posixView == null) {
            return; // not a POSIX filesystem — nothing to assert here
        }
        assertThat(Files.getPosixFilePermissions(keyFile)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
}
