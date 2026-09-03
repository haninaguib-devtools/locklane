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

    // #656: the token pair and its lifetimes.

    @Test
    void aShortLivedAccountRoundTripsItsExpiriesButNeverItsRefreshToken(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);
        Instant tokenExpiry = Instant.parse("2026-09-03T11:00:00Z");
        Instant refreshExpiry = Instant.parse("2027-03-03T10:00:00Z");

        GhAccount account = repository.insert(1L, "work", "enc-token", Set.of("repo"),
                Instant.parse("2026-09-03T10:00:00Z"), "enc-refresh", tokenExpiry, refreshExpiry);

        GhAccount found = repository.findById(account.id()).orElseThrow();
        assertThat(found.tokenExpiresAt()).isEqualTo(tokenExpiry);
        assertThat(found.refreshTokenExpiresAt()).isEqualTo(refreshExpiry);
        assertThat(found.renewalFailedAt()).isNull();
        assertThat(found.toString()).doesNotContain("enc-refresh").doesNotContain("enc-token");
        assertThat(repository.findEncryptedRefreshToken(account.id())).contains("enc-refresh");
    }

    @Test
    void findEncryptedRefreshTokenIsEmptyForAPastedTokenAccount(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);
        GhAccount pasted = repository.insert(1L, "pasted", "enc-token", Set.of("repo"), Instant.now());

        assertThat(repository.findEncryptedRefreshToken(pasted.id())).isEmpty();
        assertThat(repository.findEncryptedRefreshToken(999)).isEmpty();
    }

    @Test
    void findDueForRenewalReturnsOnlyRenewableAccountsExpiringByTheCutoff(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);
        Instant created = Instant.parse("2026-09-03T10:00:00Z");
        GhAccount soon = repository.insert(1L, "soon", "t1", Set.of("repo"), created, "r1",
                Instant.parse("2026-09-03T11:00:00Z"), null);
        repository.insert(1L, "later", "t2", Set.of("repo"), created, "r2",
                Instant.parse("2026-09-03T12:00:00Z"), null);
        repository.insert(1L, "pasted", "t3", Set.of("repo"), created);
        GhAccount failed = repository.insert(1L, "failed", "t4", Set.of("repo"), created, "r4",
                Instant.parse("2026-09-03T10:30:00Z"), null);
        repository.markRenewalFailed(failed.id(), Instant.parse("2026-09-03T10:31:00Z"));

        List<GhAccount> due = repository.findDueForRenewal(Instant.parse("2026-09-03T11:05:00Z"));

        assertThat(due).extracting(GhAccount::id).containsExactly(soon.id());
    }

    @Test
    void updateTokensStoresTheRotatedPairAndClearsAnEarlierFailure(@TempDir Path tmp) {
        GhAccountRepository repository = repository(tmp);
        GhAccount account = repository.insert(1L, "work", "t-old", Set.of("repo"), Instant.now(), "r-old",
                Instant.parse("2026-09-03T11:00:00Z"), Instant.parse("2027-03-03T10:00:00Z"));
        repository.markRenewalFailed(account.id(), Instant.parse("2026-09-03T11:01:00Z"));
        assertThat(repository.findById(account.id()).orElseThrow().renewalFailedAt()).isNotNull();

        repository.updateTokens(account.id(), "t-new", "r-new", Instant.parse("2026-09-03T12:00:00Z"),
                Instant.parse("2027-03-03T11:00:00Z"));

        GhAccount renewed = repository.findById(account.id()).orElseThrow();
        assertThat(renewed.tokenExpiresAt()).isEqualTo(Instant.parse("2026-09-03T12:00:00Z"));
        assertThat(renewed.refreshTokenExpiresAt()).isEqualTo(Instant.parse("2027-03-03T11:00:00Z"));
        assertThat(renewed.renewalFailedAt()).isNull();
        assertThat(repository.findEncryptedToken(account.id())).contains("t-new");
        assertThat(repository.findEncryptedRefreshToken(account.id())).contains("r-new");
    }

    @Test
    void needsReconnectWhenARenewalFailedOrTheRefreshTokenItselfExpired(@TempDir Path tmp) {
        Instant now = Instant.parse("2026-09-03T10:00:00Z");
        GhAccount healthy = new GhAccount(1, 1, "a", Set.of(), now, now.plusSeconds(3600), now.plusSeconds(86400), null);
        GhAccount failed = new GhAccount(2, 1, "b", Set.of(), now, now.plusSeconds(3600), now.plusSeconds(86400), now);
        GhAccount refreshDead = new GhAccount(3, 1, "c", Set.of(), now, now.plusSeconds(3600), now, null);
        GhAccount pasted = new GhAccount(4, 1, "d", Set.of(), now);

        assertThat(healthy.needsReconnect(now)).isFalse();
        assertThat(failed.needsReconnect(now)).isTrue();
        assertThat(refreshDead.needsReconnect(now)).isTrue();
        assertThat(pasted.needsReconnect(now)).isFalse();
    }

    private static GhAccountRepository repository(Path tmp) {
        return TestSqliteDatabases.newGhAccountRepository(tmp);
    }
}
