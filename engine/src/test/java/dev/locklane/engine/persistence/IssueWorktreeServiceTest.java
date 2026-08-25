package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #15's done-when: worktrees grouped by issue, non-conforming ids excluded. */
class IssueWorktreeServiceTest {

    @Test
    void returnsWorktreeIdsMatchingTheIssuePrefix(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("174-other-attempt", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("175-something", dbDir.resolve("wt3"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(174, "alice"))
                .containsExactlyInAnyOrder("174-rename-toggle", "174-other-attempt");
        assertThat(service.worktreeIdsForIssue(175, "alice")).containsExactly("175-something");
    }

    @Test
    void anIssueWithNoKnownWorktreesReturnsAnEmptyList(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(999, "alice")).isEmpty();
    }

    @Test
    void nonConformingWorktreeIdsAreExcludedRatherThanThrowing(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("main", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("not-numeric-prefix", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt3"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        // "main" and non-conforming ids never match any issue number.
        assertThat(service.worktreeIdsForIssue(174, "alice")).containsExactly("174-rename-toggle");
        for (int n = 0; n < 1000; n++) {
            assertThat(service.worktreeIdsForIssue(n, "alice")).doesNotContain("main", "not-numeric-prefix");
        }
    }

    @Test
    void doesNotFalselyMatchAnIssueNumberThatIsAPrefixOfAnother(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1740-other-issue", dbDir.resolve("wt2"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        // Issue 174's worktree must not accidentally capture issue 1740's.
        List<String> forIssue174 = service.worktreeIdsForIssue(174, "alice");
        assertThat(forIssue174).containsExactly("174-rename-toggle");
    }

    @Test
    void excludesAnotherUsersSessionButIncludesAnUnclaimedOne(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("174-alices-session", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("174-bobs-session", dbDir.resolve("wt2"), now, "bob");
        repository.recordAttach("174-unclaimed-session", dbDir.resolve("wt3"), now, null);

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(174, "alice"))
                .containsExactlyInAnyOrder("174-alices-session", "174-unclaimed-session");
    }

    @Test
    void allWorktreeIdsSpansEveryIssueButExcludesNonConformingIdsAndOtherUsers(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("175-something", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("main", dbDir.resolve("wt3"), now, "alice");
        repository.recordAttach("175-bobs-session", dbDir.resolve("wt4"), now, "bob");
        repository.recordAttach("174-unclaimed-session", dbDir.resolve("wt5"), now, null);

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.allWorktreeIds("alice")).containsExactlyInAnyOrder(
                "174-rename-toggle", "175-something", "174-unclaimed-session");
    }
}
