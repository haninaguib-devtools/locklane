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
    void unknownIssueIsEmpty(@TempDir Path root) throws IOException, InterruptedException {
        Path projectRoot = GitTestRepos.initTestRepo(root);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(root);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(root);
        long projectId = readyProject(projectRepository, projectRoot).id();
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        assertThat(service.startSession(projectId, 404)).isEmpty();
    }

    @Test
    void createsARealWorktreeDetachedAtOriginMainWithNoBranchWhenNoneExistsYet(@TempDir Path tmp) throws Exception {
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
        // No branch is minted at console-open (#340) -- the worktree sits on a detached
        // HEAD, pointed at the same commit as origin/main, and no wip/42-* branch exists.
        assertThat(GitTestRepos.currentBranch(worktreePath)).isEmpty();
        assertThat(GitTestRepos.headCommit(worktreePath)).isEqualTo(GitTestRepos.headCommit(projectRoot));
        assertThat(GitTestRepos.localBranches(projectRoot)).noneMatch(b -> b.startsWith("wip/42-"));
        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(worktreePath.toString());
    }

    @Test
    void checksOutAnAlreadyExistingLocalWipBranchInsteadOfMintingOne(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        GitTestRepos.createLocalBranch(projectRoot, "wip/43-already-in-flight");
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(43, "Renamed since the branch was made", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> result = service.startSession(projectId, 43);

        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-43");
        assertThat(worktreePath).isDirectory();
        assertThat(GitTestRepos.currentBranch(worktreePath)).isEqualTo("wip/43-already-in-flight");
        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(worktreePath.toString());
    }

    @Test
    void checksOutAWipBranchThatOnlyExistsOnOrigin(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        GitTestRepos.pushNewRemoteBranch(projectRoot, "wip/44-only-on-origin");
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(44, "Only on origin", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        Optional<WorktreeCreationService.StartedSession> result = service.startSession(projectId, 44);

        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-44");
        assertThat(worktreePath).isDirectory();
        assertThat(GitTestRepos.currentBranch(worktreePath)).isEqualTo("wip/44-only-on-origin");
        assertThat(result).map(WorktreeCreationService.StartedSession::workingDirectory)
                .contains(worktreePath.toString());
    }

    @Test
    void reopeningAnIdleDetachedWorktreeFastForwardsItToCurrentOriginMain(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(45, "Idle worktree", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        // First open: no branch yet, so the worktree lands detached at origin/main.
        service.startSession(projectId, 45);
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-45");
        String staleCommit = GitTestRepos.headCommit(worktreePath);

        // origin/main moves on without the worktree.
        GitTestRepos.commitAndPush(projectRoot, "second commit");
        String freshCommit = GitTestRepos.headCommit(projectRoot);
        assertThat(freshCommit).isNotEqualTo(staleCommit);

        // Reopening the console (no live session recorded) refreshes the idle worktree.
        service.startSession(projectId, 45);

        assertThat(GitTestRepos.headCommit(worktreePath)).isEqualTo(freshCommit);
        assertThat(GitTestRepos.currentBranch(worktreePath)).isEmpty();
    }

    @Test
    void aWorktreeOnABranchIsLeftUntouchedOnReopen(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(46, "Branch in flight", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        service.startSession(projectId, 46);
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-46");
        String startCommit = GitTestRepos.headCommit(worktreePath);
        // /t-work has since started implementation: the worktree now carries its own branch.
        GitTestRepos.checkoutNewBranch(worktreePath, "wip/46-branch-in-flight");

        GitTestRepos.commitAndPush(projectRoot, "origin moves on");

        service.startSession(projectId, 46);

        assertThat(GitTestRepos.currentBranch(worktreePath)).isEqualTo("wip/46-branch-in-flight");
        assertThat(GitTestRepos.headCommit(worktreePath)).isEqualTo(startCommit);
    }

    @Test
    void aDirtyDetachedWorktreeIsLeftUntouchedOnReopen(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(47, "Dirty worktree", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        service.startSession(projectId, 47);
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-47");
        String startCommit = GitTestRepos.headCommit(worktreePath);
        Files.writeString(worktreePath.resolve("scratch.txt"), "uncommitted work");

        GitTestRepos.commitAndPush(projectRoot, "origin moves on");

        service.startSession(projectId, 47);

        assertThat(GitTestRepos.headCommit(worktreePath)).isEqualTo(startCommit);
        assertThat(worktreePath.resolve("scratch.txt")).exists();
    }

    @Test
    void aDetachedWorktreeWithItsOwnCommitIsLeftUntouchedOnReopen(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        GhIssue issue = new GhIssue(48, "Detached with a commit", "OPEN", List.of(), "", "", "");
        WorktreeCreationService service = service(repository, projectRepository, List.of(issue));

        service.startSession(projectId, 48);
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-48");
        GitTestRepos.commitOnDetachedHead(worktreePath, "an experiment nobody pushed");
        String ownCommit = GitTestRepos.headCommit(worktreePath);

        GitTestRepos.commitAndPush(projectRoot, "origin moves on too");

        service.startSession(projectId, 48);

        assertThat(GitTestRepos.headCommit(worktreePath)).isEqualTo(ownCommit);
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
    void reopeningALegacyMainConsoleIsRefused(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        // No session record exists any more — the original console was closed (#75).
        // #341 retired the main-checkout console option: a conversation captured
        // there can only ever be resumed there, so this is refused rather than
        // resumed in the wrong directory (a worktree) or against the main checkout
        // again (no longer allowed at all).
        assertThat(service.reopenSession(projectId, 9, projectId + "-9-main-deadbeef")).isEmpty();
    }

    @Test
    void reopeningALegacyMainConsoleThatIsStillOpenIsAlsoRefused(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        String originalId = projectId + "-9-main-deadbeef";
        // The original main console is still recorded/running -- refusal does not
        // depend on the record being gone.
        repository.recordAttach(originalId, projectRoot, Instant.now(), null);
        WorktreeCreationService service = service(repository, projectRepository, List.of());

        assertThat(service.reopenSession(projectId, 9, originalId)).isEmpty();
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

    @Test
    void createsADetachedWorktreeAtOriginMainWithNoBranch(@TempDir Path tmp) throws Exception {
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-console-abcd1234");

        WorktreeCreationService.createDetachedWorktree(worktreePath, projectRoot, GitCredential.NONE);

        assertThat(worktreePath).isDirectory();
        // Detached HEAD: "branch --show-current" reports nothing for it.
        assertThat(GitTestRepos.currentBranch(worktreePath)).isEmpty();
        // No console/* branch (or any other) was minted on the project's behalf (#338).
        assertThat(GitTestRepos.branchList(projectRoot, "console/*")).isEmpty();
    }

    @Test
    void aTaskBranchCanStillBeCreatedAndCheckedOutInsideADetachedWorktree(@TempDir Path tmp) throws Exception {
        // Exercises the /t-work path this worktree is handed off to (#338 done-when):
        // a session that transitions to task work must still be able to mint and
        // check out its own wip/<id>-<slug> branch from inside a detached worktree.
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        Path worktreePath = tmp.resolve(projectRoot.getFileName() + "-console-deadbeef");
        WorktreeCreationService.createDetachedWorktree(worktreePath, projectRoot, GitCredential.NONE);

        Process checkout = new ProcessBuilder("git", "-C", worktreePath.toString(), "checkout", "-b",
                "wip/42-do-the-thing").redirectErrorStream(true).start();
        String output = new String(checkout.getInputStream().readAllBytes());
        int exit = checkout.waitFor();

        assertThat(exit).as("git checkout -b output: %s", output).isZero();
        assertThat(GitTestRepos.currentBranch(worktreePath)).isEqualTo("wip/42-do-the-thing");
    }

    @Test
    void resolvesAConversationsDirectoryFromItsRecordAndThenFromTheIssuesOwnCheckout(@TempDir Path tmp)
            throws Exception {
        // #373's title lookup needs to know where a conversation ran: Claude and
        // OpenCode file a stored conversation under its working directory.
        Path projectRoot = GitTestRepos.initTestRepo(tmp);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId = readyProject(projectRepository, projectRoot).id();
        WorktreeCreationService service = service(repository, projectRepository, List.of());
        Path issueWorktree = projectRoot.resolveSibling(WorktreeCreationService.repoName(projectRoot) + "-174");

        // No session record yet: the issue's one checkout is the answer, whether or
        // not it currently exists on disk.
        assertThat(service.conversationDirectory(projectId, 174, projectId + "-174-rename-toggle"))
                .contains(issueWorktree);

        repository.recordAttach(projectId + "-174-rename-toggle", issueWorktree,
                Instant.parse("2026-08-25T12:00:00Z"), "alice");
        assertThat(service.conversationDirectory(projectId, 174, projectId + "-174-rename-toggle"))
                .contains(issueWorktree);
        // Another issue's console, and an unknown project, resolve to nothing.
        assertThat(service.conversationDirectory(projectId, 174, projectId + "-175-something")).isEmpty();
        assertThat(service.conversationDirectory(999, 174, "999-174-rename-toggle")).isEmpty();
    }

    private static ProjectRecord readyProject(ProjectRepository projectRepository, Path projectRoot) {
        return projectRepository.createReady("proj", projectRoot.toString(), projectRoot, "main", 1L, Instant.now());
    }

    private static WorktreeCreationService service(WorktreeSessionRepository repository,
            ProjectRepository projectRepository, List<GhIssue> issues) {
        IssueWorktreeService worktreeService =
                new IssueWorktreeService(repository, TestSqliteDatabases.newNoopAuthorization());
        ProjectGhResources ghResources =
                new ProjectGhResources(projectRepository, ghAccountRepository(), tokenCipher(),
                        (path, token) -> new FixedGhClient(issues));
        return new WorktreeCreationService(ghResources, worktreeService, projectRepository, repository,
                ghAccountRepository(), tokenCipher());
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
