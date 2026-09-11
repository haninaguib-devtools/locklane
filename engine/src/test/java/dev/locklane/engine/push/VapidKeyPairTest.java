package dev.locklane.engine.push;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #860's done-when for the VAPID key file: generated once under the data dir, the same pair ever after. */
class VapidKeyPairTest {

    @Test
    void generatesAnOwnerOnlyKeyFileOnFirstRun(@TempDir Path dataDir) throws IOException {
        VapidKeyPair pair = new VapidKeyPair(dataDir.toString());

        Path keyFile = dataDir.resolve(VapidKeyPair.FILE_NAME);
        assertThat(keyFile).exists();
        assertThat(Files.readAllLines(keyFile)).hasSize(2);
        assertThat(EcKeys.fromBase64Url(pair.publicKeyBase64Url())).hasSize(65);
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
            assertThat(permissions).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        } catch (UnsupportedOperationException e) {
            // No POSIX permissions on this filesystem -- nothing to assert.
        }
    }

    @Test
    void loadsTheSamePairOnASubsequentStart(@TempDir Path dataDir) throws IOException {
        VapidKeyPair firstRun = new VapidKeyPair(dataDir.toString());
        VapidKeyPair secondRun = new VapidKeyPair(dataDir.toString());

        assertThat(secondRun.publicKeyBase64Url()).isEqualTo(firstRun.publicKeyBase64Url());
        assertThat(EcKeys.scalar(secondRun.privateKey())).isEqualTo(EcKeys.scalar(firstRun.privateKey()));
    }
}
