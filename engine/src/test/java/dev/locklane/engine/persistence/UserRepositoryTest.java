package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UserRepositoryTest {

    @Test
    void aCreatedUserIsFindableByUsername(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        repository.create("hani", "bcrypt-hash-not-a-real-password", Instant.parse("2026-08-25T12:00:00Z"));

        Optional<UserRecord> found = repository.findByUsername("hani");
        assertThat(found).isPresent();
        assertThat(found.get().username()).isEqualTo("hani");
        assertThat(found.get().passwordHash()).isEqualTo("bcrypt-hash-not-a-real-password");
    }

    @Test
    void unknownUsernameIsNotFound(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        assertThat(repository.findByUsername("nobody")).isEmpty();
    }

    @Test
    void anyExistIsFalseUntilAUserIsCreated(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        assertThat(repository.anyExist()).isFalse();

        repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));

        assertThat(repository.anyExist()).isTrue();
    }
}
