package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** #550: durable GitHub accounts, owned per-user. */
class GhAccountRepositoryTest {

    @Test
    void insertReturnsTheStoredAccountWithoutItsToken(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);

        GhAccount account = repository.insert(1L, "haninaguib", "encrypted-token", Set.of("repo", "workflow"),
                Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(account.id()).isPositive();
        assertThat(account.ownerUserId()).isEqualTo(1L);
        assertThat(account.login()).isEqualTo("haninaguib");
        assertThat(account.scopes()).containsExactlyInAnyOrder("repo", "workflow");
        assertThat(account.createdAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(account.hasWorkflowScope()).isTrue();
    }

    @Test
    void findByIdNeverExposesTheToken(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);
        GhAccount inserted = repository.insert(1L, "work", "super-secret", Set.of("repo"), Instant.now());

        // GhAccount has no token accessor at all -- this is really just proving
        // findById round-trips the same row findEncryptedToken separately reads.
        GhAccount found = repository.findById(inserted.id()).orElseThrow();

        assertThat(found).isEqualTo(inserted);
        assertThat(repository.findEncryptedToken(inserted.id())).contains("super-secret");
    }

    @Test
    void findByIdIsEmptyForAnUnknownId(@TempDir Path tmp) {
        assertThat(repository(tmp).findById(999)).isEmpty();
    }

    @Test
    void findEncryptedTokenIsEmptyForAnUnknownId(@TempDir Path tmp) {
        assertThat(repository(tmp).findEncryptedToken(999)).isEmpty();
    }

    @Test
    void findAllOwnedByOnlyReturnsThatOwnersAccountsNewestFirst(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);
        repository.insert(1L, "alice-work", "t1", Set.of("repo"), Instant.parse("2026-08-01T00:00:00Z"));
        repository.insert(1L, "alice-personal", "t2", Set.of("repo"), Instant.parse("2026-08-02T00:00:00Z"));
        repository.insert(2L, "bob-work", "t3", Set.of("repo"), Instant.parse("2026-08-01T12:00:00Z"));

        List<GhAccount> aliceAccounts = repository.findAllOwnedBy(1L);

        assertThat(aliceAccounts).extracting(GhAccount::login).containsExactly("alice-personal", "alice-work");
    }

    @Test
    void findAllOwnedByIsEmptyForAnOwnerWithNoAccounts(@TempDir Path tmp) {
        assertThat(repository(tmp).findAllOwnedBy(999)).isEmpty();
    }

    @Test
    void deleteRemovesTheAccount(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);
        GhAccount account = repository.insert(1L, "work", "t1", Set.of("repo"), Instant.now());

        repository.delete(account.id());

        assertThat(repository.findById(account.id())).isEmpty();
    }

    @Test
    void deleteOnAnUnknownIdIsANoOp(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);

        repository.delete(999);

        assertThat(repository.findAllOwnedBy(1L)).isEmpty();
    }

    @Test
    void anAccountWithNoScopesRoundTripsAsEmpty(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);

        GhAccount account = repository.insert(1L, "no-scopes", "t1", Set.of(), Instant.now());

        assertThat(repository.findById(account.id()).orElseThrow().scopes()).isEmpty();
    }

    private static GhAccountRepository repository(Path tmp) {
        return TestSqliteDatabases.newGhAccountRepository(tmp);
    }
}
