package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhClient;
import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhPullRequest;
import dev.locklane.engine.github.GhPullRequestDetail;
import dev.locklane.engine.github.ProjectGhResources;
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
 * The worktree-creating path exercises real git commands against a throwaway local
 * repository ({@link GitTestRepos} — a local bare "origin", no network, no real
 * GitHub) — for genuine confidence, not just a mocked assertion that git was "called"
 * (#20). Since #43, the checkout a worktree is created against is resolved per
 * project — each test registers a {@link ProjectRecord} pointing at its own throwaway
 * repo.
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
    void startingASessionOnAnUnknownProjectIsEmpty(@TempDir Path root) {
        WorktreeCreationService service =
                service(TestSqliteDatabases.newRepository(root), TestSqliteDatabases.newProjectRepository(root), List.of());

        assertThat(service.startSession(999, 9)).isEmpty();
    }

    @Test
    void startingASessionOnAProjectStillCloningIsEmpty(@TempDir Path root) throws IOException, InterruptedException {
        Path projectRoot = GitTestRepos.initTestRepo(root);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        long projectId = projectRepository.create("proj", "url", projectRoot, 1L, Instant.now()).id(); // still CLONING
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        assertThat(service.startSession(projectId, 9)).isEmpty();
    }

    @Test
    void reusesAnExistingWorktreeWithoutTouchingGit(@TempDir Path root) throws IOException, InterruptedException {
        Path projectRoot = GitTestRepos.initTestRepo(root);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        long projectId = readyProject(projectRepository, projectRoot).id();
        repository.recordAttach(projectId + "-9-already-running", root.resolve("wt"), Instant.now(), null);
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        // No GhIssue for #9 is supplied to the fake client, so if this reached the
        // git-creation path it would fail to find a title -- reaching a real answer
        // proves the "already exists" short-circuit ran instead.
        assertThat(service.startSession(projectId, 9)).map(WorktreeCreationService.StartedSession::worktreeId)
                .contains(projectId + "-9-already-running");
    }

    @Test
    void withoutAWorktreeTheSessionUsesTheProjectCheckoutAndNoGitWorktreeRuns(@TempDir Path root) throws IOException, InterruptedException {
        Path projectRoot = GitTestRepos.initTestRepo(root);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(11, "Console on main", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> result = service.startSession(projectId, 11, false);

        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(projectRoot.toString());
        assertThat(projectRoot.resolveSibling(projectRoot.getFileName() + "-11")).doesNotExist();
    }

    @Test
    void withoutAWorktreeEachCallStartsAFreshSessionId(@TempDir Path root) throws IOException, InterruptedException {
        Path projectRoot = GitTestRepos.initTestRepo(root);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(12, "Two consoles on main", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> first = service.startSession(projectId, 12, false);
        Optional<WorktreeCreationService.StartedSession> second = service.startSession(projectId, 12, false);

        assertThat(first).map(WorktreeCreationService.StartedSession::worktreeId).isNotEqualTo(
                second.map(WorktreeCreationService.StartedSession::worktreeId));
    }

    @Test
    void withoutAWorktreeAnUnknownIssueIsEmpty(@TempDir Path root) throws IOException, InterruptedException {
        Path projectRoot = GitTestRepos.initTestRepo(root);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        long projectId = readyProject(projectRepository, projectRoot).id();
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        assertThat(service.startSession(projectId, 404, false)).isEmpty();
    }

    @Test
    void unknownIssueIsEmpty(@TempDir Path root) throws IOException, InterruptedException {
        Path projectRoot = GitTestRepos.initTestRepo(root);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        long projectId = readyProject(projectRepository, projectRoot).id();
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        assertThat(service.startSession(projectId, 404)).isEmpty();
    }

    @Test
    void createsARealWorktreeOnANewBranch(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(42, "Add the frobnicator", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> result = service.startSession(projectId, 42);

        assertThat(result).map(WorktreeCreationService.StartedSession::worktreeId)
                .contains(projectId + "-42-add-the-frobnicator");
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-42");
        assertThat(worktreePath).isDirectory();
        // The git branch itself carries no project prefix -- each project is its own repo.
        assertThat(GitTestRepos.currentBranch(worktreePath)).isEqualTo("wip/42-add-the-frobnicator");
        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(worktreePath.toString());
    }

    @Test
    void callingItAgainForTheSameIssueReturnsTheSameIdWithoutRecreating(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(7, "Second call", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> first = service.startSession(projectId, 7);
        Optional<WorktreeCreationService.StartedSession> second = service.startSession(projectId, 7);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void twoProjectsWithTheSameIssueNumberGetIndependentWorktrees(@TempDir Path tmp) throws Exception {
        Path projectARoot = GitTestRepos.initTestRepo(tmp.resolve("a"));
        Path projectBRoot = GitTestRepos.initTestRepo(tmp.resolve("b"));
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectA = readyProject(projectRepository, projectARoot).id();
        long projectB = readyProject(projectRepository, projectBRoot).id();
        GhIssue issue = new GhIssue(5, "Same number, different repos", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> forA = service.startSession(projectA, 5);
        Optional<WorktreeCreationService.StartedSession> forB = service.startSession(projectB, 5);

        assertThat(forA).map(WorktreeCreationService.StartedSession::worktreeId).contains(projectA + "-5-same-number-different-repos");
        assertThat(forB).map(WorktreeCreationService.StartedSession::worktreeId).contains(projectB + "-5-same-number-different-repos");
        assertThat(forA).map(WorktreeCreationService.StartedSession::workingDirectory)
                .isNotEqualTo(forB.map(WorktreeCreationService.StartedSession::workingDirectory));
    }

    @Test
    void aReopenedSessionIsNeverMistakenForTheReusableWorktreeSession(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(9, "Reopen target", "OPEN", List.of(), "", "", "");
        repository.recordAttach(projectId + "-9-resume-a1b2c3d4", tmp.resolve("wt"), Instant.now(), null);
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> result = service.startSession(projectId, 9);

        // A fresh worktree is created rather than the "-resume-" session being reused.
        assertThat(result).map(WorktreeCreationService.StartedSession::worktreeId)
                .contains(projectId + "-9-reopen-target");
    }

    @Test
    void reopeningMintsAFreshIdInTheOriginalConsolesRecordedDirectory(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        Path recordedDir = tmp.resolve("still-open-console-dir");
        String originalId = projectId + "-9-reopen-target";
        repository.recordAttach(originalId, recordedDir, Instant.now(), null);
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        Optional<WorktreeCreationService.StartedSession> result = service.reopenSession(projectId, 9, originalId);

        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(recordedDir.toString());
        assertThat(result).map(WorktreeCreationService.StartedSession::worktreeId).get().asString()
                .startsWith(projectId + "-9-resume-").isNotEqualTo(originalId);
    }

    @Test
    void reopeningAClosedMainConsoleUsesTheProjectCheckoutAndAMainShapedId(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        // No session record exists any more — the original console was closed (#75).
        Optional<WorktreeCreationService.StartedSession> result =
                service.reopenSession(projectId, 9, projectId + "-9-main-deadbeef");

        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(projectRoot.toString());
        assertThat(result).map(WorktreeCreationService.StartedSession::worktreeId).get().asString()
                .startsWith(projectId + "-9-main-");
    }

    @Test
    void reopeningAClosedWorktreeConsoleRecreatesTheWorktreeWhenItIsGone(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(9, "Reopen target", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> result =
                service.reopenSession(projectId, 9, projectId + "-9-reopen-target");

        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-9");
        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(worktreePath.toString());
        assertThat(worktreePath).isDirectory();
    }

    @Test
    void reopeningAnIdFromAnotherIssueIsEmpty(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        assertThat(service.reopenSession(projectId, 9, projectId + "-8-other-issue")).isEmpty();
    }

    private static ProjectRecord readyProject(ProjectRepository projectRepository, Path projectRoot) {
        return projectRepository.createReady("proj", projectRoot.toString(), projectRoot, "main", 1L, Instant.now());
    }

    private static WorktreeCreationService service(WorktreeSessionRepository repository,
            ProjectRepository projectRepository, List<GhIssue> issues) {
        IssueWorktreeService worktreeService = new IssueWorktreeService(repository);
        ProjectGhResources ghResources =
                new ProjectGhResources(projectRepository, tokenCipher(), (path, token) -> new FixedGhClient(issues));
        return new WorktreeCreationService(ghResources, worktreeService, projectRepository, repository);
    }

    private static TokenCipher tokenCipher() {
        try {
            return new TokenCipher(new EncryptionKeyProvider(Files.createTempDirectory("gh-key").toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
