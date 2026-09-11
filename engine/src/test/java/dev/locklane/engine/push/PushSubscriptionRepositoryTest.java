package dev.locklane.engine.push;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #860's done-when for stored subscriptions: per account, auth encrypted at rest, owner-scoped removal. */
class PushSubscriptionRepositoryTest {

    private static final String ENDPOINT = "https://push.example.net/send/abc";

    @Test
    void storesTheAuthSecretEncryptedAndHandsItBackDecrypted(@TempDir Path tmp) throws IOException {
        DataSource dataSource = TestSqliteDatabases.newDataSource(tmp);
        PushSubscriptionRepository repository = new PushSubscriptionRepository(dataSource, cipher(tmp));

        repository.save(1L, ENDPOINT, "p256dh-key", "auth-secret", Instant.now());

        assertThat(repository.findAllOwnedBy(1L)).singleElement().satisfies(record -> {
            assertThat(record.ownerUserId()).isEqualTo(1L);
            assertThat(record.endpoint()).isEqualTo(ENDPOINT);
            assertThat(record.p256dh()).isEqualTo("p256dh-key");
            assertThat(record.auth()).isEqualTo("auth-secret");
        });
        String stored = new JdbcTemplate(dataSource).queryForObject("SELECT auth FROM push_subscriptions", String.class);
        assertThat(stored).isNotEqualTo("auth-secret");
    }

    @Test
    void reRegisteringAnEndpointUpdatesItInPlaceForWhoeverIsSignedInNow(@TempDir Path tmp) throws IOException {
        PushSubscriptionRepository repository = new PushSubscriptionRepository(TestSqliteDatabases.newDataSource(tmp), cipher(tmp));
        repository.save(1L, ENDPOINT, "key-1", "auth-1", Instant.now());

        repository.save(2L, ENDPOINT, "key-2", "auth-2", Instant.now());

        assertThat(repository.findAllOwnedBy(1L)).isEmpty();
        assertThat(repository.findAllOwnedBy(2L)).singleElement()
                .satisfies(record -> assertThat(record.auth()).isEqualTo("auth-2"));
    }

    @Test
    void deletionIsScopedToTheOwner(@TempDir Path tmp) throws IOException {
        PushSubscriptionRepository repository = new PushSubscriptionRepository(TestSqliteDatabases.newDataSource(tmp), cipher(tmp));
        repository.save(1L, ENDPOINT, "key", "auth", Instant.now());

        assertThat(repository.delete(2L, ENDPOINT)).isFalse();
        assertThat(repository.findAllOwnedBy(1L)).hasSize(1);
        assertThat(repository.delete(1L, ENDPOINT)).isTrue();
        assertThat(repository.findAllOwnedBy(1L)).isEmpty();
    }

    @Test
    void aGoneEndpointAndADeletedAccountAreForgottenWhoeverOwnedThem(@TempDir Path tmp) throws IOException {
        PushSubscriptionRepository repository = new PushSubscriptionRepository(TestSqliteDatabases.newDataSource(tmp), cipher(tmp));
        repository.save(1L, ENDPOINT, "key", "auth", Instant.now());
        repository.save(1L, ENDPOINT + "-2", "key", "auth", Instant.now());
        repository.save(2L, ENDPOINT + "-3", "key", "auth", Instant.now());

        repository.deleteByEndpoint(ENDPOINT);
        assertThat(repository.findAllOwnedBy(1L)).extracting(PushSubscriptionRecord::endpoint).containsExactly(ENDPOINT + "-2");

        repository.deleteAllOwnedBy(1L);
        assertThat(repository.findAllOwnedBy(1L)).isEmpty();
        assertThat(repository.findAllOwnedBy(2L)).hasSize(1);
    }

    private static TokenCipher cipher(Path tmp) throws IOException {
        return new TokenCipher(new EncryptionKeyProvider(tmp.resolve("keys").toString()));
    }
}
