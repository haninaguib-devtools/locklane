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
 * Covers #319's done-when guard: a console-created worktree is removed automatically
 * only when its issue is closed, its git status is clean, and no live session has a
 * working directory inside it — every other case is left untouched. Exercises real
 * git worktrees against a throwaway local repository (mirroring
 * {@code WorktreeCreationServiceTest}'s own approach) for genuine confidence that
 * {@code git worktree remove} actually runs, rather than only asserting a mock was
 * called.
 */
class WorktreeCleanupSweeperTest {

    @Test
    void removesAWorktreeWhoseIssueIsClosedCleanAndUnattached(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(42, "Done deal", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 42, "Done deal");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of(closed));

        List<String> removed = sweeper.sweep();

        assertThat(removed).containsExactly(worktree.worktreeId());
        assertThat(worktree.path()).doesNotExist();
        assertThat(fx.repository.find(worktree.worktreeId())).isEmpty();
    }

    @Test
    void leavesAWorktreeAloneWhoseIssueIsStillOpen(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue open = new GhIssue(43, "Still working on it", "OPEN", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 43, "Still working on it");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of(open));

        List<String> removed = sweeper.sweep();

        assertThat(removed).isEmpty();
        assertThat(worktree.path()).isDirectory();
        assertThat(fx.repository.find(worktree.worktreeId())).isPresent();
    }

    @Test
    void leavesAWorktreeAloneWhoseIssueIsNotInTheCache(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        WorktreeAndId worktree = createWorktree(fx, 44, "No such issue in the cache");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        // No GhIssue #44 supplied at all.
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).isEmpty();
        assertThat(worktree.path()).isDirectory();
    }

    @Test
    void leavesADirtyWorktreeAloneEvenWithItsIssueClosed(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(45, "Closed but dirty", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 45, "Closed but dirty");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        Files.writeString(worktree.path().resolve("scratch.txt"), "uncommitted work");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of(closed));

        List<String> removed = sweeper.sweep();

        assertThat(removed).isEmpty();
        assertThat(worktree.path()).isDirectory();
        assertThat(worktree.path().resolve("scratch.txt")).exists();
    }

    @Test
    void leavesAWorktreeAloneWhileItsOwnSessionIsLive(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(46, "Closed but someone is in there", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 46, "Closed but someone is in there");
        SessionRegistry sessionRegistry = new SessionRegistry(fx.repository);
        sessionRegistry.attach(worktree.worktreeId(), worktree.path());
        WorktreeCleanupSweeper sweeper = sweeper(fx, sessionRegistry, List.of(closed));

        try {
            List<String> removed = sweeper.sweep();

            assertThat(removed).isEmpty();
            assertThat(worktree.path()).isDirectory();
        } finally {
            sessionRegistry.close(worktree.worktreeId());
        }
    }

    @Test
    void leavesAWorktreeAloneWhileADifferentlyIdedResumeSessionSharesItsDirectory(@TempDir Path tmp) throws Exception {
        // A reopened conversation (WorktreeCreationService#reopenSession) mints a
        // fresh "-resume-" session id pointed at the same directory as the issue's
        // own worktree session -- the sweep must catch this by directory, not by id.
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(47, "Closed but reopened elsewhere", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 47, "Closed but reopened elsewhere");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        SessionRegistry sessionRegistry = new SessionRegistry(fx.repository);
        String resumeId = worktree.worktreeId() + "-resume-a1b2c3d4";
        sessionRegistry.attach(resumeId, worktree.path());
        WorktreeCleanupSweeper sweeper = sweeper(fx, sessionRegistry, List.of(closed));

        try {
            List<String> removed = sweeper.sweep();

            assertThat(removed).isEmpty();
            assertThat(worktree.path()).isDirectory();
        } finally {
            sessionRegistry.close(resumeId);
        }
    }

    @Test
    void theScheduledEntryPointRunsTheSameGuardedSweep(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(48, "Reached only via the schedule", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 48, "Reached only via the schedule");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of(closed));

        // #319's done-when: invokable both on its schedule and programmatically,
        // sharing one guard -- calling the package-private @Scheduled entry point
        // directly proves it is a thin wrapper around sweep(), not a second copy of
        // the guard logic.
        sweeper.scheduledSweep();

        assertThat(worktree.path()).doesNotExist();
        assertThat(fx.repository.find(worktree.worktreeId())).isEmpty();
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
        IssueWorktreeService worktreeService = new IssueWorktreeService(fx.repository);
        ProjectGhResources creationGhResources = new ProjectGhResources(fx.projectRepository, tokenCipher(),
                (path, token) -> new FixedGhClient(List.of(issue)));
        WorktreeCreationService creationService =
                new WorktreeCreationService(creationGhResources, worktreeService, fx.projectRepository, fx.repository);

        WorktreeCreationService.StartedSession started = creationService.startSession(fx.projectId, issueNumber).orElseThrow();
        return new WorktreeAndId(started.worktreeId(), Path.of(started.workingDirectory()));
    }

    private static WorktreeCleanupSweeper sweeper(Fixture fx, List<GhIssue> issues) {
        return sweeper(fx, new SessionRegistry(fx.repository), issues);
    }

    private static WorktreeCleanupSweeper sweeper(Fixture fx, SessionRegistry sessionRegistry, List<GhIssue> issues) {
        IssueWorktreeService worktreeService = new IssueWorktreeService(fx.repository);
        ProjectGhResources ghResources =
                new ProjectGhResources(fx.projectRepository, tokenCipher(), (path, token) -> new FixedGhClient(issues));
        return new WorktreeCleanupSweeper(worktreeService, fx.projectRepository, ghResources, sessionRegistry);
    }

    private static TokenCipher tokenCipher() {
        try {
            return new TokenCipher(new EncryptionKeyProvider(Files.createTempDirectory("gh-key").toString()));
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
