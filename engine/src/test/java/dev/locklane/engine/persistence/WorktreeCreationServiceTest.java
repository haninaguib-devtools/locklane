package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhClient;
import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhIssueCache;
import dev.locklane.engine.github.GhPullRequest;
import dev.locklane.engine.github.GhPullRequestDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worktree-creating path exercises real git commands against a throwaway local
 * repository (a local bare "origin", no network, no real GitHub) — for genuine
 * confidence, not just a mocked assertion that git was "called" (#20).
 */
class WorktreeCreationServiceTest {

    @Test
    void slugMatchesTheWipBranchConvention() {
        assertThat(WorktreeCreationService.slug("Fix the thing!")).isEqualTo("fix-the-thing");
        assertThat(WorktreeCreationService.slug("  Leading and trailing -- dashes  "))
                .isEqualTo("leading-and-trailing-dashes");
        assertThat(WorktreeCreationService.slug("x".repeat(80))).hasSize(40);
    }

    @Test
    void reusesAnExistingWorktreeWithoutTouchingGit(@TempDir Path root) {
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        repository.recordAttach("9-already-running", root.resolve("wt"), Instant.now());
        WorktreeCreationService service = service(root, repository, List.of());

        // No GhIssue for #9 is supplied to the fake client, so if this reached the
        // git-creation path it would fail to find a title -- reaching a real answer
        // proves the "already exists" short-circuit ran instead.
        assertThat(service.startSession(9)).map(WorktreeCreationService.StartedSession::worktreeId)
                .contains("9-already-running");
    }

    @Test
    void unknownIssueIsEmpty(@TempDir Path root) {
        WorktreeCreationService service = service(root, TestSqliteDatabases.newRepository(root), List.of());

        assertThat(service.startSession(404)).isEmpty();
    }

    @Test
    void createsARealWorktreeOnANewBranch(@TempDir Path tmp) throws Exception {
        Path projectRoot = initTestRepo(tmp);
        GhIssue issue = new GhIssue(42, "Add the frobnicator", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(projectRoot, TestSqliteDatabases.newRepository(tmp), List.of(issue));

        Optional<WorktreeCreationService.StartedSession> result = service.startSession(42);

        assertThat(result).map(WorktreeCreationService.StartedSession::worktreeId)
                .contains("42-add-the-frobnicator");
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-42");
        assertThat(worktreePath).isDirectory();
        assertThat(currentBranch(worktreePath)).isEqualTo("wip/42-add-the-frobnicator");
        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(worktreePath.toString());
    }

    @Test
    void callingItAgainForTheSameIssueReturnsTheSameIdWithoutRecreating(@TempDir Path tmp) throws Exception {
        Path projectRoot = initTestRepo(tmp);
        GhIssue issue = new GhIssue(7, "Second call", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service =
                service(projectRoot, TestSqliteDatabases.newRepository(tmp), List.of(issue));

        Optional<WorktreeCreationService.StartedSession> first = service.startSession(7);
        Optional<WorktreeCreationService.StartedSession> second = service.startSession(7);

        assertThat(second).isEqualTo(first);
    }

    private static WorktreeCreationService service(Path projectRoot, WorktreeSessionRepository repository,
            List<GhIssue> issues) {
        IssueWorktreeService worktreeService = new IssueWorktreeService(repository);
        GhIssueCache cache = new GhIssueCache(new FixedGhClient(issues));
        return new WorktreeCreationService(cache, worktreeService, projectRoot.toString());
    }

    /** A minimal local repo with an "origin" remote and a main branch — no network. */
    private static Path initTestRepo(Path tmp) throws IOException, InterruptedException {
        Path bare = tmp.resolve("origin.git");
        Path work = tmp.resolve("work");
        Files.createDirectories(work);

        run(tmp, "git", "init", "--bare", "-b", "main", bare.toString());
        run(tmp, "git", "init", "-b", "main", work.toString());
        run(work, "git", "config", "user.email", "test@example.com");
        run(work, "git", "config", "user.name", "Test");
        Files.writeString(work.resolve("README.md"), "test repo");
        run(work, "git", "add", "README.md");
        run(work, "git", "commit", "-m", "initial commit");
        run(work, "git", "remote", "add", "origin", bare.toString());
        run(work, "git", "push", "origin", "main");
        run(work, "git", "branch", "--set-upstream-to=origin/main", "main");
        return work;
    }

    private static String currentBranch(Path worktree) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "-C", worktree.toString(), "branch", "--show-current").start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return out;
    }

    private static void run(Path cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
        }
    }

    private static final class FixedGhClient implements GhClient {
        private final List<GhIssue> issues;

        FixedGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        @Override
        public List<GhIssue> issues() {
            return issues;
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
