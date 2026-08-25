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
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt1"), now);
        repository.recordAttach("174-other-attempt", dbDir.resolve("wt2"), now);
        repository.recordAttach("175-something", dbDir.resolve("wt3"), now);

        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(174))
                .containsExactlyInAnyOrder("174-rename-toggle", "174-other-attempt");
        assertThat(service.worktreeIdsForIssue(175)).containsExactly("175-something");
    }

    @Test
    void anIssueWithNoKnownWorktreesReturnsAnEmptyList(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        IssueWorktreeService service = new IssueWorktreeService(repository);

        assertThat(service.worktreeIdsForIssue(999)).isEmpty();
    }

    @Test
    void nonConformingWorktreeIdsAreExcludedRatherThanThrowing(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("main", dbDir.resolve("wt1"), now);
        repository.recordAttach("not-numeric-prefix", dbDir.resolve("wt2"), now);
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt3"), now);

        IssueWorktreeService service = new IssueWorktreeService(repository);

        // "main" and non-conforming ids never match any issue number.
        assertThat(service.worktreeIdsForIssue(174)).containsExactly("174-rename-toggle");
        for (int n = 0; n < 1000; n++) {
            assertThat(service.worktreeIdsForIssue(n)).doesNotContain("main", "not-numeric-prefix");
        }
    }

    @Test
    void doesNotFalselyMatchAnIssueNumberThatIsAPrefixOfAnother(@TempDir Path dbDir) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(dbDir);
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        repository.recordAttach("174-rename-toggle", dbDir.resolve("wt1"), now);
        repository.recordAttach("1740-other-issue", dbDir.resolve("wt2"), now);

        IssueWorktreeService service = new IssueWorktreeService(repository);

        // Issue 174's worktree must not accidentally capture issue 1740's.
        List<String> forIssue174 = service.worktreeIdsForIssue(174);
        assertThat(forIssue174).containsExactly("174-rename-toggle");
    }
}
