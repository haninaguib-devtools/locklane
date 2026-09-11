package dev.locklane.engine.persistence;

import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.push.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #240's cascade-delete done-when (ADR-101 Decision 4): deleting a user removes
 * every project it owns, those projects' on-disk workarea checkouts, and any
 * worktree/agent sessions scoped to them -- and nothing belonging to a different
 * user.
 */
class UserCascadeDeleteServiceTest {
    // Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories and the
    // /console and /consoles REST paths below keep their persisted and on-the-wire shape: compatibility
    // surfaces kept under ADR-112 (#766 renamed only the identifiers).

    @Test
    void deletesEveryOwnedProjectItsWorkareaAndItsSessions(@TempDir Path tmp) throws Exception {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        IssueWorktreeService issueWorktreeService = new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization());
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(
                projectRepository, tmp.resolve("workarea").toString(), Runnable::run, issueWorktreeService,
                tokenCipher(tmp), TestSqliteDatabases.newGhAccountRepository(tmp));
        UserCascadeDeleteService cascadeDeleteService =
                new UserCascadeDeleteService(projectRepository, checkoutService, pushSubscriptions(tmp));

        Path workarea = tmp.resolve("workarea").resolve("1").resolve("mine");
        Files.createDirectories(workarea);
        ProjectRecord owned = projectRepository.create("mine", "url", workarea, 1L, Instant.now());
        sessions.recordAttach(owned.id() + "-174-rename-toggle", tmp.resolve("wt"), Instant.now(), "alice");

        PushSubscriptionRepository pushSubscriptions = pushSubscriptions(tmp);
        pushSubscriptions.save(1L, "https://push.example.net/alice", "key", "auth", Instant.now());
        pushSubscriptions.save(2L, "https://push.example.net/bob", "key", "auth", Instant.now());

        cascadeDeleteService.deleteEverythingOwnedBy(1L);

        assertThat(projectRepository.findById(owned.id())).isEmpty();
        assertThat(workarea).doesNotExist();
        assertThat(issueWorktreeService.hasAnySessions(owned.id())).isFalse();
        // The account's Web Push subscriptions (#860) go with it; another account's stay.
        assertThat(pushSubscriptions.findAllOwnedBy(1L)).isEmpty();
        assertThat(pushSubscriptions.findAllOwnedBy(2L)).hasSize(1);
    }

    @Test
    void leavesAnotherUsersProjectsUntouched(@TempDir Path tmp) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(projectRepository,
                tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(TestSqliteDatabases.newRepository(tmp), TestSqliteDatabases.newNoopAuthorization()),
                tokenCipher(tmp), TestSqliteDatabases.newGhAccountRepository(tmp));
        UserCascadeDeleteService cascadeDeleteService =
                new UserCascadeDeleteService(projectRepository, checkoutService, pushSubscriptions(tmp));

        ProjectRecord bobsProject = projectRepository.create("bobs", "url", tmp.resolve("bobs"), 2L, Instant.now());

        cascadeDeleteService.deleteEverythingOwnedBy(1L);

        assertThat(projectRepository.findById(bobsProject.id())).isPresent();
    }

    @Test
    void aUserWithNoOwnedProjectsIsANoOp(@TempDir Path tmp) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(projectRepository,
                tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(TestSqliteDatabases.newRepository(tmp), TestSqliteDatabases.newNoopAuthorization()),
                tokenCipher(tmp), TestSqliteDatabases.newGhAccountRepository(tmp));
        UserCascadeDeleteService cascadeDeleteService =
                new UserCascadeDeleteService(projectRepository, checkoutService, pushSubscriptions(tmp));

        cascadeDeleteService.deleteEverythingOwnedBy(999L);

        assertThat(projectRepository.findAll()).isEmpty();
    }

    /** The account's Web Push subscriptions (#860), over the same on-disk database as the repositories above. */
    private static PushSubscriptionRepository pushSubscriptions(Path tmp) {
        return new PushSubscriptionRepository(TestSqliteDatabases.newDataSource(tmp), tokenCipher(tmp));
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
