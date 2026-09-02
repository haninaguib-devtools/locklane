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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #320's project-page worktree list and manual remove action: the list's
 * clean/dirty and session-attached columns, and that removal applies the exact same
 * three-part guard as #319's periodic sweep ({@link WorktreeCleanupSweeper}) — refusing
 * with a clear reason for an open issue, a dirty worktree, or an attached session, and
 * otherwise removing. Exercises real git worktrees against a throwaway local
 * repository, the same approach {@code WorktreeCleanupSweeperTest} uses.
 */
class ProjectWorktreesServiceTest {

    @Test
    void listsClosedCleanUnattachedRowsForTheirOwnProjectOnly(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId worktree = createWorktree(fx, 42, "Done deal");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesService service = service(fx, List.of());

        List<ProjectWorktreesService.WorktreeRow> rows = service.listForProject(fx.projectId);

        assertThat(rows).containsExactly(
                new ProjectWorktreesService.WorktreeRow(worktree.worktreeId(), 42, worktree.path().toString(), true, false));
        // A different project id sees none of this project's rows.
        assertThat(service.listForProject(fx.projectId + 1)).isEmpty();
    }

    @Test
    void listReflectsADirtyWorktree(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId worktree = createWorktree(fx, 43, "Getting there");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        Files.writeString(worktree.path().resolve("scratch.txt"), "uncommitted");
        ProjectWorktreesService service = service(fx, List.of());

        List<ProjectWorktreesService.WorktreeRow> rows = service.listForProject(fx.projectId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).clean()).isFalse();
    }

    @Test
    void listReflectsAnAttachedSession(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId worktree = createWorktree(fx, 44, "In progress");
        SessionRegistry sessionRegistry = new SessionRegistry(fx.repository);
        sessionRegistry.attach(worktree.worktreeId(), worktree.path());
        try {
            ProjectWorktreesService service = service(fx, sessionRegistry, List.of());

            List<ProjectWorktreesService.WorktreeRow> rows = service.listForProject(fx.projectId);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).sessionAttached()).isTrue();
        } finally {
            sessionRegistry.close(worktree.worktreeId());
        }
    }

    @Test
    void removeSucceedsWhenClosedCleanAndUnattached(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(45, "Done", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 45, "Done");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesService service = service(fx, List.of(closed));

        ProjectWorktreesService.RemovalResult result = service.remove(fx.projectId, worktree.worktreeId());

        assertThat(result.found()).isTrue();
        assertThat(result.removed()).isTrue();
        assertThat(result.refusalReason()).isNull();
        assertThat(worktree.path()).doesNotExist();
        assertThat(fx.repository.find(worktree.worktreeId())).isEmpty();
    }

    @Test
    void removeRefusesAnOpenIssueWithAClearReason(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue open = new GhIssue(46, "Still open", "OPEN", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 46, "Still open");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesService service = service(fx, List.of(open));

        ProjectWorktreesService.RemovalResult result = service.remove(fx.projectId, worktree.worktreeId());

        assertThat(result.found()).isTrue();
        assertThat(result.removed()).isFalse();
        assertThat(result.refusalReason()).contains("still open");
        assertThat(worktree.path()).isDirectory();
    }

    @Test
    void removeRefusesADirtyWorktreeWithAClearReason(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(47, "Closed but dirty", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 47, "Closed but dirty");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        Files.writeString(worktree.path().resolve("scratch.txt"), "uncommitted work");
        ProjectWorktreesService service = service(fx, List.of(closed));

        ProjectWorktreesService.RemovalResult result = service.remove(fx.projectId, worktree.worktreeId());

        assertThat(result.removed()).isFalse();
        assertThat(result.refusalReason()).contains("uncommitted changes");
        assertThat(worktree.path()).isDirectory();
    }

    @Test
    void removeRefusesAnAttachedSessionWithAClearReason(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(48, "Closed but attached", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 48, "Closed but attached");
        SessionRegistry sessionRegistry = new SessionRegistry(fx.repository);
        sessionRegistry.attach(worktree.worktreeId(), worktree.path());
        try {
            ProjectWorktreesService service = service(fx, sessionRegistry, List.of(closed));

            ProjectWorktreesService.RemovalResult result = service.remove(fx.projectId, worktree.worktreeId());

            assertThat(result.removed()).isFalse();
            assertThat(result.refusalReason()).contains("attached");
            assertThat(worktree.path()).isDirectory();
        } finally {
            sessionRegistry.close(worktree.worktreeId());
        }
    }

    @Test
    void removeReportsNotFoundForAWorktreeIdOutsideTheProject(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(49, "Belongs to a different project", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 49, "Belongs to a different project");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesService service = service(fx, List.of(closed));

        ProjectWorktreesService.RemovalResult wrongProject = service.remove(fx.projectId + 1, worktree.worktreeId());
        ProjectWorktreesService.RemovalResult unknownId = service.remove(fx.projectId, "no-such-worktree");

        assertThat(wrongProject.found()).isFalse();
        assertThat(unknownId.found()).isFalse();
        assertThat(worktree.path()).isDirectory();
    }

    @Test
    void runCleanupNowInvokesTheSameSweepAndTheListReflectsItAfterward(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(50, "Swept on demand", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 50, "Swept on demand");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        ProjectWorktreesService service = service(fx, List.of(closed));

        List<String> removed = service.runCleanupNow();

        assertThat(removed).containsExactly(worktree.worktreeId());
        assertThat(service.listForProject(fx.projectId)).isEmpty();
    }

    @Test
    void listIncludesACleanDetachedProjectConsoleWorktreeWithNoIssueNumber(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId console = createProjectConsoleWorktree(fx);
        ProjectWorktreesService service = service(fx, List.of());

        List<ProjectWorktreesService.WorktreeRow> rows = service.listForProject(fx.projectId);

        assertThat(rows).containsExactly(
                new ProjectWorktreesService.WorktreeRow(console.worktreeId(), null, console.path().toString(), true, false));
    }

    @Test
    void removeSucceedsForACleanDetachedProjectConsoleWorktree(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId console = createProjectConsoleWorktree(fx);
        ProjectWorktreesService service = service(fx, List.of());

        ProjectWorktreesService.RemovalResult result = service.remove(fx.projectId, console.worktreeId());

        assertThat(result.found()).isTrue();
        assertThat(result.removed()).isTrue();
        assertThat(console.path()).doesNotExist();
    }

    @Test
    void removeRefusesAProjectConsoleWorktreeWithABranchCheckedOut(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId console = createProjectConsoleWorktree(fx);
        run(console.path(), "git", "checkout", "-b", "wip/1-do-the-thing");
        ProjectWorktreesService service = service(fx, List.of());

        ProjectWorktreesService.RemovalResult result = service.remove(fx.projectId, console.worktreeId());

        assertThat(result.found()).isTrue();
        assertThat(result.removed()).isFalse();
        assertThat(result.refusalReason()).contains("outgrown scratch use");
        assertThat(console.path()).isDirectory();
    }

    @Test
    void removeRefusesAProjectConsoleWorktreeWithCommitsNotOnOriginMain(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId console = createProjectConsoleWorktree(fx);
        run(console.path(), "git", "commit", "--allow-empty", "-m", "unpushed work on detached HEAD");
        ProjectWorktreesService service = service(fx, List.of());

        ProjectWorktreesService.RemovalResult result = service.remove(fx.projectId, console.worktreeId());

        assertThat(result.found()).isTrue();
        assertThat(result.removed()).isFalse();
        assertThat(result.refusalReason()).contains("not yet reachable from origin/main");
        assertThat(console.path()).isDirectory();
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
                new WorktreeCreationService(creationGhResources, worktreeService, fx.projectRepository, fx.repository);

        WorktreeCreationService.StartedSession started = creationService.startSession(fx.projectId, issueNumber).orElseThrow();
        return new WorktreeAndId(started.worktreeId(), Path.of(started.workingDirectory()));
    }

    /** A project-console-shaped sibling worktree (#339) — detached at origin/main, matching the naming convention {@link WorktreeCleanupSweeper#allProjectConsoleWorktrees()} discovers. */
    private static WorktreeAndId createProjectConsoleWorktree(Fixture fx) {
        String suffix = "abcd1234";
        Path worktreePath =
                fx.projectRoot().resolveSibling(WorktreeCreationService.repoName(fx.projectRoot()) + "-console-" + suffix);
        WorktreeCreationService.createDetachedWorktree(worktreePath, fx.projectRoot());
        return new WorktreeAndId(fx.projectId() + "-console-" + suffix, worktreePath);
    }

    private static ProjectWorktreesService service(Fixture fx, List<GhIssue> issues) {
        return service(fx, new SessionRegistry(fx.repository), issues);
    }

    private static ProjectWorktreesService service(Fixture fx, SessionRegistry sessionRegistry, List<GhIssue> issues) {
        IssueWorktreeService worktreeService =
                new IssueWorktreeService(fx.repository, TestSqliteDatabases.newNoopAuthorization());
        ProjectGhResources ghResources =
                new ProjectGhResources(fx.projectRepository, ghAccountRepository(), tokenCipher(),
                (path, token) -> new FixedGhClient(issues));
        WorktreeCleanupSweeper sweeper = new WorktreeCleanupSweeper(worktreeService, fx.projectRepository, ghResources,
                sessionRegistry, ghAccountRepository(), tokenCipher());
        return new ProjectWorktreesService(worktreeService, sweeper, sessionRegistry);
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
