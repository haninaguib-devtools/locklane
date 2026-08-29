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
    void aNewUserHasNoTotpSecretAndTwoFactorOff(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        UserRecord created = repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));

        assertThat(created.totpSecret()).isNull();
        assertThat(created.totpEnabled()).isFalse();
    }

    @Test
    void aStartedEnrollmentIsStoredButLeavesTwoFactorOff(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);
        repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));

        repository.startTotpEnrollment("hani", "encrypted-secret");

        UserRecord found = repository.findByUsername("hani").orElseThrow();
        assertThat(found.totpSecret()).isEqualTo("encrypted-secret");
        assertThat(found.totpEnabled()).isFalse();
    }

    @Test
    void enablingTurnsTwoFactorOnAndDisablingForgetsTheSecret(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);
        repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));
        repository.startTotpEnrollment("hani", "encrypted-secret");

        assertThat(repository.enableTotp("hani", "encrypted-secret"))
                .as("one row changed — the enrollment was there to confirm")
                .isEqualTo(1);
        assertThat(repository.findByUsername("hani").orElseThrow().totpEnabled()).isTrue();

        repository.disableTotp("hani");
        UserRecord after = repository.findByUsername("hani").orElseThrow();
        assertThat(after.totpEnabled()).isFalse();
        assertThat(after.totpSecret()).isNull();
    }

    @Test
    void enablingWithNoSecretStoredDoesNothing(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);
        repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));

        assertThat(repository.enableTotp("hani", "encrypted-secret"))
                .as("no rows changed, and the caller has to be able to tell — reporting 2FA on "
                        + "here would promise a second factor the account cannot produce")
                .isEqualTo(0);

        assertThat(repository.findByUsername("hani").orElseThrow().totpEnabled())
                .as("2FA must never be on against a NULL secret — that is an account locked out "
                        + "of a factor it has no way to produce")
                .isFalse();
    }

    @Test
    void enablingWithAStaleSecretDoesNothing(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);
        repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));
        repository.startTotpEnrollment("hani", "second-encrypted-secret");

        assertThat(repository.enableTotp("hani", "first-encrypted-secret"))
                .as("the row's secret was replaced by a later enroll, so the ciphertext this "
                        + "caller verified matches no row — 0 rows changed, not 1")
                .isEqualTo(0);

        assertThat(repository.findByUsername("hani").orElseThrow().totpEnabled())
                .as("2FA must never be enabled against a secret different from the one that was "
                        + "actually verified")
                .isFalse();
    }

    @Test
    void unknownUsernameIsNotFound(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        assertThat(repository.findByUsername("nobody")).isEmpty();
    }

    @Test
    void aNewUserDefaultsToRoleUserAndDoesNotRequireAPasswordChange(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        UserRecord created = repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));

        assertThat(created.role()).isEqualTo(UserRecord.Role.USER);
        assertThat(created.mustChangePassword()).isFalse();
    }

    @Test
    void createWithAnExplicitRoleStoresThatRole(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        UserRecord created = repository.create(
                "admin", "hash", Instant.parse("2026-08-25T12:00:00Z"), UserRecord.Role.ADMIN);

        assertThat(created.role()).isEqualTo(UserRecord.Role.ADMIN);
        assertThat(repository.findByUsername("admin").orElseThrow().role())
                .isEqualTo(UserRecord.Role.ADMIN);
    }

    @Test
    void changePasswordReplacesTheHashAndClearsMustChangePassword(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);
        repository.create("hani", "old-hash", Instant.parse("2026-08-25T12:00:00Z"));
        repository.changePassword("hani", "new-hash");

        UserRecord after = repository.findByUsername("hani").orElseThrow();
        assertThat(after.passwordHash()).isEqualTo("new-hash");
        assertThat(after.mustChangePassword())
                .as("a plain voluntary change already satisfies whatever must_change_password was asking for")
                .isFalse();
    }

    @Test
    void anyExistIsFalseUntilAUserIsCreated(@TempDir Path dbDir) {
        UserRepository repository = TestSqliteDatabases.newUserRepository(dbDir);

        assertThat(repository.anyExist()).isFalse();

        repository.create("hani", "hash", Instant.parse("2026-08-25T12:00:00Z"));

        assertThat(repository.anyExist()).isTrue();
    }
}
