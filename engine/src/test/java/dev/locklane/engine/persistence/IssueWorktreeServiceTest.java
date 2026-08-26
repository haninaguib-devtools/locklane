package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers #15's done-when: worktrees grouped by project+issue, non-conforming ids excluded (#43). */
class IssueWorktreeServiceTest {

    @Test
    void returnsWorktreeIdsMatchingTheProjectAndIssuePrefix(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-174-other-attempt", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("1-175-something", dbDir.resolve("wt3"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "alice"))
                .containsExactlyInAnyOrder("1-174-rename-toggle", "1-174-other-attempt");
        assertThat(service.worktreeIdsForIssue(1, 175, "alice")).containsExactly("1-175-something");
    }

    @Test
    void aDifferentProjectWithTheSameIssueNumberIsExcluded(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("2-174-unrelated-repo", dbDir.resolve("wt2"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "alice")).containsExactly("1-174-rename-toggle");
        assertThat(service.worktreeIdsForIssue(2, 174, "alice")).containsExactly("2-174-unrelated-repo");
    }

    @Test
    void anIssueWithNoKnownWorktreesReturnsAnEmptyList(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(1, 999, "alice")).isEmpty();
    }

    @Test
    void nonConformingWorktreeIdsAreExcludedRatherThanThrowing(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("main", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("not-numeric-prefix", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt3"), now, "alice"); // only one numeric segment
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt4"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        // "main", a non-numeric id, and a single-segment-numeric id never match any project/issue.
        assertThat(service.worktreeIdsForIssue(1, 174, "alice")).containsExactly("1-174-rename-toggle");
        for (int n = 0; n < 1000; n++) {
            assertThat(service.worktreeIdsForIssue(1, n, "alice"))
                    .doesNotContain("main", "not-numeric-prefix", "174-rename-toggle");
        }
    }

    @Test
    void doesNotFalselyMatchAnIssueNumberThatIsAPrefixOfAnother(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-1740-other-issue", dbDir.resolve("wt2"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        // Issue 174's worktree must not accidentally capture issue 1740's.
        List<String> forIssue174 = service.worktreeIdsForIssue(1, 174, "alice");
        assertThat(forIssue174).containsExactly("1-174-rename-toggle");
    }

    @Test
    void excludesAnotherUsersSessionButIncludesAnUnclaimedOne(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-alices-session", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-174-bobs-session", dbDir.resolve("wt2"), now, "bob");
        repository.recordAttach("1-174-unclaimed-session", dbDir.resolve("wt3"), now, null);

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(1, 174, "alice"))
                .containsExactlyInAnyOrder("1-174-alices-session", "1-174-unclaimed-session");
    }

    @Test
    void allWorktreeIdsSpansEveryIssueInOneProjectButExcludesOtherProjectsAndUsers(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("1-174-rename-toggle", dbDir.resolve("wt1"), now, "alice");
        repository.recordAttach("1-175-something", dbDir.resolve("wt2"), now, "alice");
        repository.recordAttach("main", dbDir.resolve("wt3"), now, "alice");
        repository.recordAttach("1-175-bobs-session", dbDir.resolve("wt4"), now, "bob");
        repository.recordAttach("1-174-unclaimed-session", dbDir.resolve("wt5"), now, null);
        repository.recordAttach("2-174-other-project", dbDir.resolve("wt6"), now, "alice");

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.allWorktreeIds(1, "alice")).containsExactlyInAnyOrder(
                "1-174-rename-toggle", "1-175-something", "1-174-unclaimed-session");
    }
}
