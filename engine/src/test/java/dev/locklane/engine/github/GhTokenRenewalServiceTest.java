package dev.locklane.engine.github;

import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #656: the renewal pass and the on-401 renewal, against a fake {@link GhDeviceFlow}
 * and a fixed clock — no network, no sleeping.
 */
class GhTokenRenewalServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    @Test
    void theScheduledPassRenewsOnlyAccountsInsideTheMarginAndStoresTheRotatedPair(@TempDir Path tmp)
            throws IOException {
        Fixture f = new Fixture(tmp, "client-id");
        GhAccount due = f.shortLived("due", "ghr_due", NOW.plusSeconds(200));
        GhAccount later = f.shortLived("later", "ghr_later", NOW.plusSeconds(3600));
        f.deviceFlow.refreshResults.add(new GhDeviceFlow.PollResult.Success("ghu_new", "bearer", "repo", 3600,
                "ghr_new", 15811200));

        f.service.renewDue();

        assertThat(f.deviceFlow.refreshCalls).containsExactly(List.of("client-id", "ghr_due"));
        GhAccount renewed = f.accounts.findById(due.id()).orElseThrow();
        assertThat(renewed.tokenExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(renewed.refreshTokenExpiresAt()).isEqualTo(NOW.plusSeconds(15811200));
        assertThat(renewed.renewalFailedAt()).isNull();
        assertThat(f.cipher.decrypt(f.accounts.findEncryptedToken(due.id()).orElseThrow())).isEqualTo("ghu_new");
        assertThat(f.cipher.decrypt(f.accounts.findEncryptedRefreshToken(due.id()).orElseThrow())).isEqualTo("ghr_new");
        assertThat(f.accounts.findById(later.id()).orElseThrow().tokenExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void aRenewalEvictsEveryProjectOnTheAccountExactlyOnce(@TempDir Path tmp) throws IOException {
        Fixture f = new Fixture(tmp, "client-id");
        GhAccount account = f.shortLived("work", "ghr_work", NOW.plusSeconds(60));
        ProjectRecord a = f.project("a", account);
        ProjectRecord b = f.project("b", account);
        ProjectRecord other = f.project("other", f.shortLived("other", "ghr_other", NOW.plusSeconds(9999)));
        f.deviceFlow.refreshResults.add(new GhDeviceFlow.PollResult.Success("ghu_new", "bearer", "repo", 3600,
                "ghr_new", null));

        f.service.renewDue();

        assertThat(f.evictions).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(f.evictions).doesNotContain(other.id());
    }

    @Test
    void aPastedTokenAccountIsNeverTouched(@TempDir Path tmp) throws IOException {
        Fixture f = new Fixture(tmp, "client-id");
        GhAccount pasted = f.accounts.insert(1L, "pasted", f.cipher.encrypt("ghp_x"), Set.of("repo"), NOW);
        ProjectRecord project = f.project("p", pasted);

        f.service.renewDue();
        boolean renewed = f.service.renewForProject(project.id());

        assertThat(renewed).isFalse();
        assertThat(f.deviceFlow.refreshCalls).isEmpty();
        assertThat(f.evictions).isEmpty();
        assertThat(f.accounts.findById(pasted.id()).orElseThrow().needsReconnect(NOW)).isFalse();
    }

    @Test
    void aRefusedRenewalMarksTheAccountAndItIsNotRetried(@TempDir Path tmp) throws IOException {
        Fixture f = new Fixture(tmp, "client-id");
        GhAccount account = f.shortLived("work", "ghr_work", NOW.plusSeconds(60));
        ProjectRecord project = f.project("p", account);
        f.deviceFlow.refreshResults.add(new GhDeviceFlow.PollResult.Error("bad_refresh_token"));

        f.service.renewDue();
        f.service.renewDue();
        boolean renewedOn401 = f.service.renewForProject(project.id());

        assertThat(f.deviceFlow.refreshCalls).hasSize(1);
        assertThat(renewedOn401).isFalse();
        GhAccount marked = f.accounts.findById(account.id()).orElseThrow();
        assertThat(marked.renewalFailedAt()).isEqualTo(NOW);
        assertThat(marked.needsReconnect(NOW)).isTrue();
        assertThat(f.evictions).isEmpty();
    }

    @Test
    void renewForProjectRenewsRightAwayAndRenewalDidNotHelpMarksTheAccount(@TempDir Path tmp) throws IOException {
        Fixture f = new Fixture(tmp, "client-id");
        GhAccount account = f.shortLived("work", "ghr_work", NOW.plusSeconds(3600)); // not due yet
        ProjectRecord project = f.project("p", account);
        f.deviceFlow.refreshResults.add(new GhDeviceFlow.PollResult.Success("ghu_new", "bearer", "repo", 3600,
                "ghr_new", 15811200));

        boolean renewed = f.service.renewForProject(project.id());

        assertThat(renewed).isTrue();
        assertThat(f.deviceFlow.refreshCalls).containsExactly(List.of("client-id", "ghr_work"));
        assertThat(f.evictions).containsExactly(project.id());

        f.service.renewalDidNotHelp(project.id());

        assertThat(f.accounts.findById(account.id()).orElseThrow().renewalFailedAt()).isEqualTo(NOW);
        assertThat(f.service.renewForProject(project.id())).isFalse();
        assertThat(f.deviceFlow.refreshCalls).hasSize(1);
    }

    @Test
    void renewForProjectIsFalseForAProjectWithNoAccount(@TempDir Path tmp) throws IOException {
        Fixture f = new Fixture(tmp, "client-id");
        ProjectRecord project = f.projects.createReady("p", "url", tmp.resolve("p"), "main", 1L, NOW);

        assertThat(f.service.renewForProject(project.id())).isFalse();
        assertThat(f.service.renewForProject(999L)).isFalse();
        assertThat(f.deviceFlow.refreshCalls).isEmpty();
    }

    @Test
    void anExpiredRefreshTokenMarksTheAccountWithoutCallingGitHub(@TempDir Path tmp) throws IOException {
        Fixture f = new Fixture(tmp, "client-id");
        GhAccount account = f.accounts.insert(1L, "idle", f.cipher.encrypt("ghu_x"), Set.of("repo"), NOW,
                f.cipher.encrypt("ghr_x"), NOW.plusSeconds(60), NOW.minusSeconds(1));

        f.service.renewDue();

        assertThat(f.deviceFlow.refreshCalls).isEmpty();
        assertThat(f.accounts.findById(account.id()).orElseThrow().renewalFailedAt()).isEqualTo(NOW);
    }

    @Test
    void noClientIdMarksTheAccountRatherThanCallingGitHubWithNothing(@TempDir Path tmp) throws IOException {
        Fixture f = new Fixture(tmp, "");
        GhAccount account = f.shortLived("work", "ghr_work", NOW.plusSeconds(60));

        f.service.renewDue();

        assertThat(f.deviceFlow.refreshCalls).isEmpty();
        assertThat(f.accounts.findById(account.id()).orElseThrow().needsReconnect(NOW)).isTrue();
    }

    @Test
    void aSuccessfulRenewalClearsAnEarlierFailureMark(@TempDir Path tmp) throws IOException {
        // The mark exists to stop retries; a human reconnecting is the ordinary way
        // out, but a renewal that works (a later manual renew, say) is proof enough.
        Fixture f = new Fixture(tmp, "client-id");
        GhAccount account = f.shortLived("work", "ghr_work", NOW.plusSeconds(60));
        f.accounts.markRenewalFailed(account.id(), NOW.minusSeconds(100));
        f.deviceFlow.refreshResults.add(new GhDeviceFlow.PollResult.Success("ghu_new", "bearer", "repo", 3600,
                "ghr_new", null));

        assertThat(f.service.renew(account.id())).isFalse(); // marked: never retried on its own
        f.accounts.updateTokens(account.id(), f.cipher.encrypt("t"), f.cipher.encrypt("ghr_work"),
                NOW.plusSeconds(60), null);
        assertThat(f.service.renew(account.id())).isTrue();

        assertThat(f.accounts.findById(account.id()).orElseThrow().renewalFailedAt()).isNull();
    }

    private static final class Fixture {
        final GhAccountRepository accounts;
        final ProjectRepository projects;
        final TokenCipher cipher;
        final FakeDeviceFlow deviceFlow = new FakeDeviceFlow();
        final List<Long> evictions = new ArrayList<>();
        final GhTokenRenewalService service;

        Fixture(Path tmp, String clientId) throws IOException {
            accounts = TestSqliteDatabases.newGhAccountRepository(tmp);
            projects = TestSqliteDatabases.newProjectRepository(tmp);
            cipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
            ProjectGhResources resources = new ProjectGhResources(projects, accounts, cipher, (path, token) -> {
                throw new AssertionError("no gh client is ever built here");
            }) {
                @Override
                public void evict(long projectId) {
                    evictions.add(projectId);
                    super.evict(projectId);
                }
            };
            service = new GhTokenRenewalService(accounts, projects, cipher, deviceFlow, resources, clientId,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        GhAccount shortLived(String login, String refreshToken, Instant tokenExpiresAt) {
            return accounts.insert(1L, login, cipher.encrypt("ghu_" + login), Set.of("repo"), NOW,
                    cipher.encrypt(refreshToken), tokenExpiresAt, NOW.plusSeconds(15811200));
        }

        ProjectRecord project(String name, GhAccount account) {
            ProjectRecord project = projects.createReady(name, "url", Path.of("/work", name), "main", 1L, NOW);
            projects.setGithubAccountId(project.id(), account.id());
            return project;
        }
    }

    private static final class FakeDeviceFlow implements GhDeviceFlow {
        final List<List<String>> refreshCalls = new ArrayList<>();
        final java.util.ArrayDeque<PollResult> refreshResults = new java.util.ArrayDeque<>();

        @Override
        public DeviceCode start(String clientId, String scope) {
            throw new AssertionError("renewal never starts a device flow");
        }

        @Override
        public PollResult poll(String clientId, String deviceCode) {
            throw new AssertionError("renewal never polls a device flow");
        }

        @Override
        public PollResult refresh(String clientId, String refreshToken) {
            refreshCalls.add(List.of(clientId, refreshToken));
            PollResult next = refreshResults.poll();
            return next != null ? next : new PollResult.Error("no refresh result queued");
        }
    }
}
