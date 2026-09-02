package dev.locklane.engine.persistence;

import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
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
 * worktree/console sessions scoped to them -- and nothing belonging to a different
 * user.
 */
class UserCascadeDeleteServiceTest {

    @Test
    void deletesEveryOwnedProjectItsWorkareaAndItsSessions(@TempDir Path tmp) throws Exception {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        IssueWorktreeService issueWorktreeService = new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization());
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(
                projectRepository, tmp.resolve("workarea").toString(), Runnable::run, issueWorktreeService,
                tokenCipher(tmp), TestSqliteDatabases.newGhAccountRepository(tmp));
        UserCascadeDeleteService cascadeDeleteService =
                new UserCascadeDeleteService(projectRepository, checkoutService);

        Path workarea = tmp.resolve("workarea").resolve("1").resolve("mine");
        Files.createDirectories(workarea);
        ProjectRecord owned = projectRepository.create("mine", "url", workarea, 1L, Instant.now());
        sessions.recordAttach(owned.id() + "-174-rename-toggle", tmp.resolve("wt"), Instant.now(), "alice");

        cascadeDeleteService.deleteEverythingOwnedBy(1L);

        assertThat(projectRepository.findById(owned.id())).isEmpty();
        assertThat(workarea).doesNotExist();
        assertThat(issueWorktreeService.hasAnySessions(owned.id())).isFalse();
    }

    @Test
    void leavesAnotherUsersProjectsUntouched(@TempDir Path tmp) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(projectRepository,
                tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(TestSqliteDatabases.newRepository(tmp), TestSqliteDatabases.newNoopAuthorization()),
                tokenCipher(tmp), TestSqliteDatabases.newGhAccountRepository(tmp));
        UserCascadeDeleteService cascadeDeleteService =
                new UserCascadeDeleteService(projectRepository, checkoutService);

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
                new UserCascadeDeleteService(projectRepository, checkoutService);

        cascadeDeleteService.deleteEverythingOwnedBy(999L);

        assertThat(projectRepository.findAll()).isEmpty();
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
