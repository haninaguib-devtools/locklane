package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link SessionRegistry#hasLiveSessionIn} (#319's cleanup sweep guard): a
 * directory-based check, since a live session's id need not match a worktree's own
 * id (a reopened conversation mints a fresh "-resume-" id at the same directory).
 */
class SessionRegistryLiveSessionDirectoryTest {

    @Test
    void trueWhenALiveSessionsWorkingDirectoryMatchesExactly(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = newRegistry(dbDir);
        registry.attach("session-a", workDir);

        assertThat(registry.hasLiveSessionIn(workDir)).isTrue();
    }

    @Test
    void trueWhenALiveSessionsWorkingDirectoryIsNestedInsideTheGivenOne(@TempDir Path dbDir, @TempDir Path workDir)
            throws Exception {
        SessionRegistry registry = newRegistry(dbDir);
        Path nested = workDir.resolve("nested");
        java.nio.file.Files.createDirectories(nested);
        registry.attach("session-nested", nested);

        assertThat(registry.hasLiveSessionIn(workDir)).isTrue();
    }

    @Test
    void falseWhenNoLiveSessionsDirectoryMatches(@TempDir Path dbDir, @TempDir Path workDir, @TempDir Path otherDir) {
        SessionRegistry registry = newRegistry(dbDir);
        registry.attach("session-elsewhere", otherDir);

        assertThat(registry.hasLiveSessionIn(workDir)).isFalse();
    }

    @Test
    void falseWhenNoSessionIsLiveAtAll(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = newRegistry(dbDir);

        assertThat(registry.hasLiveSessionIn(workDir)).isFalse();
    }

    @Test
    void falseOnceTheOnlyMatchingSessionIsClosed(@TempDir Path dbDir, @TempDir Path workDir) {
        SessionRegistry registry = newRegistry(dbDir);
        registry.attach("session-b", workDir);
        assertThat(registry.hasLiveSessionIn(workDir)).isTrue();

        registry.close("session-b");

        assertThat(registry.hasLiveSessionIn(workDir)).isFalse();
    }

    @Test
    void trueWhenADifferentlyIdedSessionSharesTheSameDirectory(@TempDir Path dbDir, @TempDir Path workDir) {
        // The exact case #319's sweep must catch: a reopened conversation's session
        // id never equals the original worktree session's id, but both can point at
        // the same directory.
        SessionRegistry registry = newRegistry(dbDir);
        registry.attach("1-174-rename-toggle", workDir);
        registry.attach("1-174-resume-a1b2c3d4", workDir);

        assertThat(registry.hasLiveSessionIn(workDir)).isTrue();

        registry.close("1-174-rename-toggle");
        // The resume session is still live, at the same directory.
        assertThat(registry.hasLiveSessionIn(workDir)).isTrue();

        registry.close("1-174-resume-a1b2c3d4");
        assertThat(registry.hasLiveSessionIn(workDir)).isFalse();
    }

    private static SessionRegistry newRegistry(Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        return new SessionRegistry(repository);
    }
}
