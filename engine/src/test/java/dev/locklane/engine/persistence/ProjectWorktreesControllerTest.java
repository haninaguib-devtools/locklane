package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhClient;
import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhPullRequest;
import dev.locklane.engine.github.GhPullRequestDetail;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP shape #320 adds around {@link ProjectWorktreesService}: 200 with the row
 * list, 404 for a worktree id this project does not have, 409 with the guard's own
 * refusal message, 204 on an actual removal, and the cleanup trigger's response.
 */
class ProjectWorktreesControllerTest {

    @Test
    void listsRowsForTheProject(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId worktree = createWorktree(fx, 60, "Listed");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesController controller = controller(fx, List.of());

        List<ProjectWorktreesService.WorktreeRow> rows = controller.list(fx.projectId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).issueNumber()).isEqualTo(60);
        assertThat(rows.get(0).clean()).isTrue();
        assertThat(rows.get(0).sessionAttached()).isFalse();
    }

    @Test
    void removingAnUnknownWorktreeIs404(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        ProjectWorktreesController controller = controller(fx, List.of());

        var response = controller.remove(fx.projectId, "no-such-worktree");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removingAWorktreeWithAnOpenIssueIs409WithTheGuardsReason(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue open = new GhIssue(61, "Still open", "OPEN", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 61, "Still open");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesController controller = controller(fx, List.of(open));

        var response = controller.remove(fx.projectId, worktree.worktreeId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).contains("still open");
        assertThat(worktree.path()).isDirectory();
    }

    @Test
    void removingAnEligibleWorktreeIs204(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(62, "Done", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 62, "Done");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesController controller = controller(fx, List.of(closed));

        var response = controller.remove(fx.projectId, worktree.worktreeId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(worktree.path()).doesNotExist();
    }

    @Test
    void cleanupReportsWhatItRemoved(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(63, "Swept", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 63, "Swept");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesController controller = controller(fx, List.of(closed));

        var response = controller.cleanup(fx.projectId);

        assertThat(response.get("removed")).containsExactly(worktree.worktreeId());
    }

    private record WorktreeAndId(String worktreeId, Path path) {
    }

    private record Fixture(Path projectRoot, long projectId, WorktreeSessionRepository repository,
            ProjectRepository projectRepository) {
    }

    private static Fixture fixture(Path tmp) throws IOException, InterruptedException {
        Path projectRoot = initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = projectRepository.createReady("proj", projectRoot.toString(), projectRoot, "main", 1L, Instant.now()).id();
        return new Fixture(projectRoot, projectId, repository, projectRepository);
    }

    private static WorktreeAndId createWorktree(Fixture fx, int issueNumber, String title) throws IOException, InterruptedException {
        GhIssue issue = new GhIssue(issueNumber, title, "OPEN", List.of(), "", "", "");
        IssueWorktreeService worktreeService =
                new IssueWorktreeService(fx.repository, TestSqliteDatabases.newNoopAuthorization());
        ProjectGhResources creationGhResources = new ProjectGhResources(fx.projectRepository, ghAccountRepository(), tokenCipher(),
                (path, token) -> new FixedGhClient(List.of(issue)));
        WorktreeCreationService creationService =
                new WorktreeCreationService(creationGhResources, worktreeService, fx.projectRepository, fx.repository,
                        ghAccountRepository(), tokenCipher());

        WorktreeCreationService.StartedSession started = creationService.startSession(fx.projectId, issueNumber).orElseThrow();
        return new WorktreeAndId(started.worktreeId(), Path.of(started.workingDirectory()));
    }

    private static ProjectWorktreesController controller(Fixture fx, List<GhIssue> issues) {
        IssueWorktreeService worktreeService =
                new IssueWorktreeService(fx.repository, TestSqliteDatabases.newNoopAuthorization());
        SessionRegistry sessionRegistry = new SessionRegistry(fx.repository);
        ProjectGhResources ghResources =
                new ProjectGhResources(fx.projectRepository, ghAccountRepository(), tokenCipher(),
                (path, token) -> new FixedGhClient(issues));
        WorktreeCleanupSweeper sweeper = new WorktreeCleanupSweeper(worktreeService, fx.projectRepository, ghResources,
                sessionRegistry, ghAccountRepository(), tokenCipher());
        ProjectWorktreesService service = new ProjectWorktreesService(worktreeService, sweeper, sessionRegistry);
        return new ProjectWorktreesController(service);
    }

    private static TokenCipher tokenCipher() {
        try {
            return new TokenCipher(new EncryptionKeyProvider(Files.createTempDirectory("gh-key").toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GhAccountRepository ghAccountRepository() {
        try {
            return TestSqliteDatabases.newGhAccountRepository(Files.createTempDirectory("gh-accounts"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A minimal local repo with an "origin" remote and a main branch — no network. */
    private static Path initTestRepo(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path bare = dir.resolve("origin.git");
        Path work = dir.resolve("work");
        Files.createDirectories(work);

        run(dir, "git", "init", "--bare", "-b", "main", bare.toString());
        run(dir, "git", "init", "-b", "main", work.toString());
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
