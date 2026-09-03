package dev.locklane.engine.github;

import dev.locklane.engine.persistence.GhAccountRepository;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** #550: GitHub accounts Locklane owns — paste-a-token, list, remove, and the device flow, all against fakes. */
class GhAccountsServiceTest {

    @Test
    void addByTokenValidatesAndStoresItEncrypted(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.of(
                new GhTokenIntrospector.Introspection("pasted-login", Set.of("repo", "workflow"))));

        GhAccountsService.AddResult result = service.addByToken(1L, " ghp_pasted ");

        assertThat(result).isInstanceOf(GhAccountsService.AddResult.Added.class);
        GhAccount added = ((GhAccountsService.AddResult.Added) result).account();
        assertThat(added.login()).isEqualTo("pasted-login");
        assertThat(added.scopes()).containsExactlyInAnyOrder("repo", "workflow");
        assertThat(added.ownerUserId()).isEqualTo(1L);
        GhAccountRepository repository = TestSqliteDatabases.newGhAccountRepository(tmp);
        String stored = repository.findEncryptedToken(added.id()).orElseThrow();
        assertThat(stored).isNotEqualTo("ghp_pasted"); // encrypted, not plaintext
        assertThat(tokenCipher(tmp).decrypt(stored)).isEqualTo("ghp_pasted");
    }

    @Test
    void addByTokenRejectsOneThatCannotBeVerified(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.empty());

        GhAccountsService.AddResult result = service.addByToken(1L, "ghp_bad");

        assertThat(result).isInstanceOf(GhAccountsService.AddResult.Invalid.class);
        assertThat(TestSqliteDatabases.newGhAccountRepository(tmp).findAllOwnedBy(1L)).isEmpty();
    }

    @Test
    void addByTokenRejectsOneWithoutTheRepoScope(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.of(
                new GhTokenIntrospector.Introspection("no-repo-scope", Set.of("read:org"))));

        GhAccountsService.AddResult result = service.addByToken(1L, "ghp_narrow");

        assertThat(result).isInstanceOf(GhAccountsService.AddResult.Invalid.class);
        assertThat(((GhAccountsService.AddResult.Invalid) result).message()).contains("repo");
        assertThat(TestSqliteDatabases.newGhAccountRepository(tmp).findAllOwnedBy(1L)).isEmpty();
    }

    @Test
    void addByTokenRejectsABlankToken(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> {
            throw new AssertionError("must not even try to introspect a blank token");
        });

        GhAccountsService.AddResult result = service.addByToken(1L, "   ");

        assertThat(result).isInstanceOf(GhAccountsService.AddResult.Invalid.class);
    }

    @Test
    void accountsForOnlyReturnsThatOwnersAccounts(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.of(
                new GhTokenIntrospector.Introspection("someone", Set.of("repo"))));
        service.addByToken(1L, "alice-token");
        service.addByToken(2L, "bob-token");

        assertThat(service.accountsFor(1L)).hasSize(1);
        assertThat(service.accountsFor(2L)).hasSize(1);
        assertThat(service.accountsFor(1L).get(0).ownerUserId()).isEqualTo(1L);
    }

    @Test
    void removeDeletesAnOwnedUnreferencedAccount(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.of(
                new GhTokenIntrospector.Introspection("work", Set.of("repo"))));
        GhAccount account = ((GhAccountsService.AddResult.Added) service.addByToken(1L, "work-token")).account();

        GhAccountsService.RemoveResult result = service.remove(1L, account.id());

        assertThat(result).isInstanceOf(GhAccountsService.RemoveResult.Removed.class);
        assertThat(service.accountsFor(1L)).isEmpty();
    }

    @Test
    void removeOnAnUnknownAccountIsNotFound(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.empty());

        assertThat(service.remove(1L, 999L)).isInstanceOf(GhAccountsService.RemoveResult.NotFound.class);
    }

    @Test
    void removeOnAnotherOwnersAccountIsNotFound(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.of(
                new GhTokenIntrospector.Introspection("work", Set.of("repo"))));
        GhAccount account = ((GhAccountsService.AddResult.Added) service.addByToken(2L, "work-token")).account();

        GhAccountsService.RemoveResult result = service.remove(1L, account.id());

        assertThat(result).isInstanceOf(GhAccountsService.RemoveResult.NotFound.class);
        assertThat(service.accountsFor(2L)).hasSize(1);
    }

    @Test
    void removeRefusesAnAccountStillUsedByAProject(@TempDir Path tmp) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        GhAccountsService service = service(tmp, projectRepository,
                token -> Optional.of(new GhTokenIntrospector.Introspection("work", Set.of("repo"))));
        GhAccount account = ((GhAccountsService.AddResult.Added) service.addByToken(1L, "work-token")).account();
        long projectId = projectRepository.create("uses-it", "url", tmp.resolve("uses-it"), 1L, Instant.now()).id();
        projectRepository.setGithubAccountId(projectId, account.id());

        GhAccountsService.RemoveResult result = service.remove(1L, account.id());

        assertThat(result).isInstanceOf(GhAccountsService.RemoveResult.InUse.class);
        assertThat(((GhAccountsService.RemoveResult.InUse) result).projectNames()).containsExactly("uses-it");
        assertThat(service.accountsFor(1L)).hasSize(1);
    }

    // #550: the device flow — a fake GhDeviceFlow instead of ever reaching github.com.

    @Test
    void startDeviceFlowIsNotConfiguredWithNoClientId(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, "", new FakeDeviceFlow(), token -> Optional.empty());

        GhAccountsService.DeviceFlowStartResult result = service.startDeviceFlow(1L);

        assertThat(result).isInstanceOf(GhAccountsService.DeviceFlowStartResult.NotConfigured.class);
    }

    @Test
    void startDeviceFlowReturnsTheCodeToShowImmediately(@TempDir Path tmp) {
        FakeDeviceFlow deviceFlow = new FakeDeviceFlow();
        deviceFlow.startResult = new GhDeviceFlow.DeviceCode("device-abc", "USER-CODE", "https://github.com/login/device",
                900, 1);
        GhAccountsService service = service(tmp, "client-id", deviceFlow, token -> Optional.empty());

        GhAccountsService.DeviceFlowStartResult result = service.startDeviceFlow(1L);

        assertThat(result).isInstanceOf(GhAccountsService.DeviceFlowStartResult.Started.class);
        var started = (GhAccountsService.DeviceFlowStartResult.Started) result;
        assertThat(started.userCode()).isEqualTo("USER-CODE");
        assertThat(started.verificationUri()).isEqualTo("https://github.com/login/device");
    }

    @Test
    void startDeviceFlowFailedIsReportedWhenGitHubCannotBeReached(@TempDir Path tmp) {
        FakeDeviceFlow deviceFlow = new FakeDeviceFlow();
        deviceFlow.startThrows = new RuntimeException("network down");
        GhAccountsService service = service(tmp, "client-id", deviceFlow, token -> Optional.empty());

        GhAccountsService.DeviceFlowStartResult result = service.startDeviceFlow(1L);

        assertThat(result).isInstanceOf(GhAccountsService.DeviceFlowStartResult.Failed.class);
    }

    @Test
    void aCompletedDeviceFlowAddsTheAccountAndReportsComplete(@TempDir Path tmp) throws InterruptedException {
        FakeDeviceFlow deviceFlow = new FakeDeviceFlow();
        deviceFlow.startResult = new GhDeviceFlow.DeviceCode("device-abc", "USER-CODE", "https://github.com/login/device",
                900, 0);
        deviceFlow.pollResults.add(new GhDeviceFlow.PollResult.Success("device-token"));
        CountDownLatch settled = new CountDownLatch(1);
        GhAccountsService service = service(tmp, "client-id", deviceFlow,
                token -> Optional.of(new GhTokenIntrospector.Introspection("device-login", Set.of("repo", "workflow"))),
                settled);

        GhAccountsService.DeviceFlowStartResult started = service.startDeviceFlow(1L);
        String flowId = ((GhAccountsService.DeviceFlowStartResult.Started) started).flowId();
        assertThat(settled.await(5, TimeUnit.SECONDS)).isTrue();

        GhAccountsService.DeviceFlowStatus status = service.statusOf(1L, flowId).orElseThrow();
        assertThat(status.status()).isEqualTo(GhAccountsService.DeviceFlowStatus.Status.COMPLETE);
        assertThat(status.account().login()).isEqualTo("device-login");
        assertThat(service.accountsFor(1L)).hasSize(1);
    }

    @Test
    void aShortLivedDeviceFlowTokenIsStoredAsAnEncryptedPairWithItsExpiries(@TempDir Path tmp)
            throws InterruptedException {
        // #656: the shape GitHub sends every OAuth App registered since 2026-08-14.
        FakeDeviceFlow deviceFlow = new FakeDeviceFlow();
        deviceFlow.pollResults.add(new GhDeviceFlow.PollResult.Success("ghu_access", "bearer", "repo", 3600,
                "ghr_refresh", 15811200));
        CountDownLatch settled = new CountDownLatch(1);
        GhAccountsService service = service(tmp, "client-id", deviceFlow,
                token -> Optional.of(new GhTokenIntrospector.Introspection("device-login", Set.of("repo"))), settled);
        Instant before = Instant.now();

        service.startDeviceFlow(1L);
        assertThat(settled.await(5, TimeUnit.SECONDS)).isTrue();

        GhAccount account = service.accountsFor(1L).get(0);
        assertThat(account.tokenExpiresAt()).isBetween(before.plusSeconds(3600), Instant.now().plusSeconds(3600));
        assertThat(account.refreshTokenExpiresAt()).isBetween(before.plusSeconds(15811200),
                Instant.now().plusSeconds(15811200));
        assertThat(account.renewalFailedAt()).isNull();
        assertThat(account.needsReconnect(Instant.now())).isFalse();
        GhAccountRepository repository = TestSqliteDatabases.newGhAccountRepository(tmp);
        String storedRefresh = repository.findEncryptedRefreshToken(account.id()).orElseThrow();
        assertThat(storedRefresh).isNotEqualTo("ghr_refresh");
        assertThat(tokenCipher(tmp).decrypt(storedRefresh)).isEqualTo("ghr_refresh");
        assertThat(tokenCipher(tmp).decrypt(repository.findEncryptedToken(account.id()).orElseThrow()))
                .isEqualTo("ghu_access");
    }

    @Test
    void aPastedTokenStoresNoRefreshTokenAndNoExpiry(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, token -> Optional.of(
                new GhTokenIntrospector.Introspection("pasted-login", Set.of("repo"))));

        GhAccount added = ((GhAccountsService.AddResult.Added) service.addByToken(1L, "ghp_pasted")).account();

        assertThat(added.tokenExpiresAt()).isNull();
        assertThat(added.refreshTokenExpiresAt()).isNull();
        assertThat(TestSqliteDatabases.newGhAccountRepository(tmp).findEncryptedRefreshToken(added.id())).isEmpty();
    }

    @Test
    void aDeniedDeviceFlowReportsFailedWithNoAccountStored(@TempDir Path tmp) throws InterruptedException {
        FakeDeviceFlow deviceFlow = new FakeDeviceFlow();
        deviceFlow.startResult = new GhDeviceFlow.DeviceCode("device-abc", "USER-CODE", "https://github.com/login/device",
                900, 0);
        deviceFlow.pollResults.add(new GhDeviceFlow.PollResult.Denied());
        CountDownLatch settled = new CountDownLatch(1);
        GhAccountsService service = service(tmp, "client-id", deviceFlow, token -> Optional.empty(), settled);

        GhAccountsService.DeviceFlowStartResult started = service.startDeviceFlow(1L);
        String flowId = ((GhAccountsService.DeviceFlowStartResult.Started) started).flowId();
        assertThat(settled.await(5, TimeUnit.SECONDS)).isTrue();

        GhAccountsService.DeviceFlowStatus status = service.statusOf(1L, flowId).orElseThrow();
        assertThat(status.status()).isEqualTo(GhAccountsService.DeviceFlowStatus.Status.FAILED);
        assertThat(status.errorMessage()).contains("declined");
        assertThat(service.accountsFor(1L)).isEmpty();
    }

    @Test
    void statusOfAnUnknownFlowIsEmpty(@TempDir Path tmp) {
        GhAccountsService service = service(tmp, "client-id", new FakeDeviceFlow(), token -> Optional.empty());

        assertThat(service.statusOf(1L, "no-such-flow")).isEmpty();
    }

    @Test
    void statusOfAnotherOwnersFlowIsEmpty(@TempDir Path tmp) throws InterruptedException {
        FakeDeviceFlow deviceFlow = new FakeDeviceFlow();
        deviceFlow.startResult = new GhDeviceFlow.DeviceCode("device-abc", "USER-CODE", "https://github.com/login/device",
                900, 0);
        deviceFlow.pollResults.add(new GhDeviceFlow.PollResult.Expired());
        CountDownLatch settled = new CountDownLatch(1);
        GhAccountsService service = service(tmp, "client-id", deviceFlow, token -> Optional.empty(), settled);
        GhAccountsService.DeviceFlowStartResult started = service.startDeviceFlow(1L);
        String flowId = ((GhAccountsService.DeviceFlowStartResult.Started) started).flowId();
        settled.await(5, TimeUnit.SECONDS);

        assertThat(service.statusOf(2L, flowId)).isEmpty();
    }

    private static GhAccountsService service(Path tmp, java.util.function.Function<String,
            Optional<GhTokenIntrospector.Introspection>> introspect) {
        return service(tmp, TestSqliteDatabases.newProjectRepository(tmp), introspect);
    }

    private static GhAccountsService service(Path tmp, ProjectRepository projectRepository,
            java.util.function.Function<String, Optional<GhTokenIntrospector.Introspection>> introspect) {
        return service(tmp, projectRepository, "", new FakeDeviceFlow(), introspect, new CountDownLatch(0));
    }

    private static GhAccountsService service(Path tmp, String clientId, GhDeviceFlow deviceFlow,
            java.util.function.Function<String, Optional<GhTokenIntrospector.Introspection>> introspect) {
        return service(tmp, TestSqliteDatabases.newProjectRepository(tmp), clientId, deviceFlow, introspect,
                new CountDownLatch(0));
    }

    private static GhAccountsService service(Path tmp, String clientId, GhDeviceFlow deviceFlow,
            java.util.function.Function<String, Optional<GhTokenIntrospector.Introspection>> introspect,
            CountDownLatch pollSettled) {
        return service(tmp, TestSqliteDatabases.newProjectRepository(tmp), clientId, deviceFlow, introspect, pollSettled);
    }

    private static GhAccountsService service(Path tmp, ProjectRepository projectRepository, String clientId,
            GhDeviceFlow deviceFlow, java.util.function.Function<String, Optional<GhTokenIntrospector.Introspection>> introspect,
            CountDownLatch pollSettled) {
        GhAccountRepository accountRepository = TestSqliteDatabases.newGhAccountRepository(tmp);
        TokenCipher tokenCipher = tokenCipher(tmp);
        GhTokenIntrospector fakeIntrospector = new FakeIntrospector(introspect);
        java.util.concurrent.Executor pollExecutor = task -> Thread.ofVirtual().start(() -> {
            task.run();
            pollSettled.countDown();
        });
        return new GhAccountsService(accountRepository, projectRepository, tokenCipher, fakeIntrospector, deviceFlow,
                pollExecutor, clientId);
    }

    /** Stands in {@link GhTokenIntrospector#introspect(String)} without ever running the real {@code gh} CLI. */
    private static final class FakeIntrospector extends GhTokenIntrospector {
        private final java.util.function.Function<String, Optional<Introspection>> fn;

        FakeIntrospector(java.util.function.Function<String, Optional<Introspection>> fn) {
            super(token -> {
                throw new AssertionError("must not run the real gh subprocess");
            });
            this.fn = fn;
        }

        @Override
        public Optional<Introspection> introspect(String token) {
            return fn.apply(token);
        }
    }

    private static final class FakeDeviceFlow implements GhDeviceFlow {
        GhDeviceFlow.DeviceCode startResult =
                new GhDeviceFlow.DeviceCode("device-code", "CODE", "https://github.com/login/device", 900, 0);
        RuntimeException startThrows;
        final java.util.concurrent.ConcurrentLinkedQueue<PollResult> pollResults = new java.util.concurrent.ConcurrentLinkedQueue<>();

        @Override
        public DeviceCode start(String clientId, String scope) {
            if (startThrows != null) {
                throw startThrows;
            }
            return startResult;
        }

        @Override
        public PollResult poll(String clientId, String deviceCode) {
            PollResult next = pollResults.poll();
            return next != null ? next : new PollResult.Pending();
        }

        @Override
        public PollResult refresh(String clientId, String refreshToken) {
            throw new AssertionError("sign-in never refreshes a token");
        }
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
