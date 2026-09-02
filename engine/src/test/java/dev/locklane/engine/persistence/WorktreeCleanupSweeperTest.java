package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhAccount;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #319's done-when guard: a console-created worktree is removed automatically
 * only when its issue is closed, its git status is clean, and no live session has a
 * working directory inside it — every other case is left untouched. Exercises real
 * git worktrees against a throwaway local repository (mirroring
 * {@code WorktreeCreationServiceTest}'s own approach) for genuine confidence that
 * {@code git worktree remove} actually runs, rather than only asserting a mock was
 * called.
 *
 * <p>Also covers #342's done-when: once a worktree is actually removed, its local
 * branch goes with it if and only if `git branch -d` (never `-D`) considers it safe to
 * delete — a fully-merged branch disappears, an unmerged one survives untouched.
 *
 * <p>Also covers #583/ADR-108's done-when: the project-console guard judges "landed"
 * against the project's own recorded default branch on origin, not a hardcoded
 * {@code origin/main} — the {@code ...OnAMasterTrunk}/{@code ...IsMaster} tests below
 * build their fixture on a {@code master}-trunk repo (via {@link #fixture(Path,
 * String)}) to prove that.
 */
class WorktreeCleanupSweeperTest {

    @Test
    void removesAWorktreeWhoseIssueIsClosedCleanAndUnattached(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(42, "Done deal", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 42, "Done deal");
        String branch = currentBranch(worktree.path());
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of(closed));

        List<String> removed = sweeper.sweep();

        assertThat(removed).containsExactly(worktree.worktreeId());
        assertThat(worktree.path()).doesNotExist();
        assertThat(fx.repository.find(worktree.worktreeId())).isEmpty();
        // #342: the branch was never ahead of main (nothing was ever committed on it),
        // so it is trivially merged -- `git branch -d` deletes it once the worktree
        // that held it is gone.
        assertThat(branchExists(fx.projectRoot(), branch)).isFalse();
    }

    @Test
    void leavesAnUnmergedBranchAloneAfterRemovingItsWorktree(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        GhIssue closed = new GhIssue(49, "Shipped nothing yet", "CLOSED", List.of(), "", "", "");
        WorktreeAndId worktree = createWorktree(fx, 49, "Shipped nothing yet");
        String branch = currentBranch(worktree.path());
        // A real commit on the branch, never merged into main -- exactly the case
        // `git branch -d` (never `-D`) must refuse, per #342's done-when.
        Files.writeString(worktree.path().resolve("wip.txt"), "unshipped work");
        run(worktree.path(), "git", "add", "wip.txt");
        run(worktree.path(), "git", "commit", "-m", "unshipped work");
        fx.repository.recordAttach(worktree.worktreeId(), worktree.path(), Instant.now(), null);
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of(closed));

        List<String> removed = sweeper.sweep();

        // The worktree itself is still removed -- an unmerged branch is not a reason
        // to leave the (clean, unattached, closed-issue) worktree in place.
        assertThat(removed).containsExactly(worktree.worktreeId());
        assertThat(worktree.path()).doesNotExist();
        // git's own merge check refused the branch delete; nothing retried or forced
        // it, so the branch -- and its one unshipped commit -- survives.
        assertThat(branchExists(fx.projectRoot(), branch)).isTrue();
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

    // --- #339/ADR-104: the sweep as backstop for orphaned project-console worktrees ---

    @Test
    void sweepRemovesAnOrphanedProjectConsoleWorktreeThatIsCleanDetachedAndHasNoStrayCommits(@TempDir Path tmp)
            throws Exception {
        Fixture fx = fixture(tmp);
        // No session record at all -- simulating the ordinary case (tab-close already
        // deleted it) as well as a crash where none was ever recorded.
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).containsExactly(console.worktreeId());
        assertThat(console.path()).doesNotExist();
    }

    @Test
    void sweepLeavesADirtyProjectConsoleWorktreeAlone(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        Files.writeString(console.path().resolve("scratch.txt"), "uncommitted work");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).isEmpty();
        assertThat(console.path()).isDirectory();
    }

    @Test
    void sweepLeavesAProjectConsoleWorktreeAloneWhoseBranchCarriesUnlandedWork(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        checkoutBranchWithRealCommit(console.path(), "wip/1-do-the-thing", "unshipped.txt", "not yet on main");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).isEmpty();
        assertThat(console.path()).isDirectory();
    }

    @Test
    void sweepRemovesAProjectConsoleWorktreeWhoseBranchHasAlreadyLandedOnOriginMain(@TempDir Path tmp)
            throws Exception {
        // #554/ADR-107: the branch's own commit is squash-merged into main under a
        // different SHA -- git worktrees share the same repo's refs, so squashing
        // straight onto fx.projectRoot()'s checked-out `main` and pushing simulates
        // exactly what a real squash-merge PR does, without needing a second clone.
        Fixture fx = fixture(tmp);
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        checkoutBranchWithRealCommit(console.path(), "wip/529-bump-revision", "revision.txt", "0.1.9-SNAPSHOT");
        run(fx.projectRoot(), "git", "merge", "--squash", "wip/529-bump-revision");
        run(fx.projectRoot(), "git", "commit", "-m", "Bump revision (#530)");
        run(fx.projectRoot(), "git", "push", "origin", "main");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).containsExactly(console.worktreeId());
        assertThat(console.path()).doesNotExist();
    }

    @Test
    void sweepLeavesAProjectConsoleWorktreeAloneWithCommitsNotOnOriginMain(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        run(console.path(), "git", "commit", "--allow-empty", "-m", "unpushed work on detached HEAD");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).isEmpty();
        assertThat(console.path()).isDirectory();
    }

    // --- #583/ADR-108: the guard judges against the project's own recorded trunk,
    // not a hardcoded origin/main ---

    @Test
    void sweepRemovesAnOrphanedProjectConsoleWorktreeOnAProjectWhoseTrunkIsMaster(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, "master");
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx, "origin/master");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).containsExactly(console.worktreeId());
        assertThat(console.path()).doesNotExist();
    }

    @Test
    void sweepLeavesAProjectConsoleWorktreeAloneWithCommitsNotOnAMasterTrunk(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp, "master");
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx, "origin/master");
        run(console.path(), "git", "commit", "--allow-empty", "-m", "unpushed work on detached HEAD");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).isEmpty();
        assertThat(console.path()).isDirectory();
    }

    @Test
    void sweepRemovesAProjectConsoleWorktreeWhoseBranchHasAlreadyLandedOnAMasterTrunk(@TempDir Path tmp)
            throws Exception {
        // Same #554/ADR-107 squash-merge-equivalence case as
        // sweepRemovesAProjectConsoleWorktreeWhoseBranchHasAlreadyLandedOnOriginMain,
        // on a project whose recorded trunk is master rather than main -- this is
        // exactly the case that errored out (and so was silently left alone) before
        // #583: the guard used to compare against a literal, and here nonexistent,
        // origin/main.
        Fixture fx = fixture(tmp, "master");
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx, "origin/master");
        checkoutBranchWithRealCommit(console.path(), "wip/529-bump-revision", "revision.txt", "0.1.9-SNAPSHOT");
        run(fx.projectRoot(), "git", "merge", "--squash", "wip/529-bump-revision");
        run(fx.projectRoot(), "git", "commit", "-m", "Bump revision (#530)");
        run(fx.projectRoot(), "git", "push", "origin", "master");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).containsExactly(console.worktreeId());
        assertThat(console.path()).doesNotExist();
    }

    @Test
    void removalRefusalReasonForProjectConsoleNamesOriginMasterOnAMasterTrunkProject(@TempDir Path tmp)
            throws Exception {
        Fixture fx = fixture(tmp, "master");
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx, "origin/master");
        checkoutBranchWithRealCommit(console.path(), "wip/1-do-the-thing", "unshipped.txt", "not yet on master");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        Optional<String> reason = sweeper.removalRefusalReasonForProjectConsole(
                new WorktreeCleanupSweeper.ProjectConsoleWorktree(fx.projectId, console.worktreeId(), console.path()));

        assertThat(reason).contains(
                "a branch is checked out in this worktree, and its work has not landed on origin/master yet — it has outgrown scratch use, so it is left alone");
    }

    @Test
    void sweepLeavesAProjectConsoleWorktreeAloneWhileItsSessionIsLive(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        SessionRegistry sessionRegistry = new SessionRegistry(fx.repository);
        sessionRegistry.attach(console.worktreeId(), console.path());
        WorktreeCleanupSweeper sweeper = sweeper(fx, sessionRegistry, List.of());

        try {
            List<String> removed = sweeper.sweep();

            assertThat(removed).isEmpty();
            assertThat(console.path()).isDirectory();
        } finally {
            sessionRegistry.close(console.worktreeId());
        }
    }

    @Test
    void removalRefusalReasonForProjectConsoleNamesTheFirstFailingCheck(@TempDir Path tmp) throws Exception {
        Fixture fx = fixture(tmp);
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        checkoutBranchWithRealCommit(console.path(), "wip/1-do-the-thing", "unshipped.txt", "not yet on main");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        Optional<String> reason = sweeper.removalRefusalReasonForProjectConsole(
                new WorktreeCleanupSweeper.ProjectConsoleWorktree(fx.projectId, console.worktreeId(), console.path()));

        assertThat(reason).contains(
                "a branch is checked out in this worktree, and its work has not landed on origin/main yet — it has outgrown scratch use, so it is left alone");
    }

    @Test
    void removalRefusalReasonForProjectConsoleIsEmptyOnceItsCheckedOutBranchHasLanded(@TempDir Path tmp)
            throws Exception {
        Fixture fx = fixture(tmp);
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        checkoutBranchWithRealCommit(console.path(), "wip/529-bump-revision", "revision.txt", "0.1.9-SNAPSHOT");
        run(fx.projectRoot(), "git", "merge", "--squash", "wip/529-bump-revision");
        run(fx.projectRoot(), "git", "commit", "-m", "Bump revision (#530)");
        run(fx.projectRoot(), "git", "push", "origin", "main");
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        Optional<String> reason = sweeper.removalRefusalReasonForProjectConsole(
                new WorktreeCleanupSweeper.ProjectConsoleWorktree(fx.projectId, console.worktreeId(), console.path()));

        assertThat(reason).isEmpty();
    }

    // #551: the ancestor-check fetch carries the project's chosen account's token as
    // GH_TOKEN. A local-path "origin" (this fixture's own shape) never actually
    // consults credentials, so this proves the wiring runs end-to-end without
    // regressing the existing guard, not that the token specifically reaches the
    // subprocess -- see the task record's Deviations for why that stronger assertion
    // isn't practical against a local-path remote.

    @Test
    void sweepRemovesAnOrphanedProjectConsoleWorktreeWhenTheProjectHasAChosenAccount(@TempDir Path tmp)
            throws Exception {
        Fixture fx = fixture(tmp);
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        GhAccountRepository ghAccountRepository = TestSqliteDatabases.newGhAccountRepository(tmp);
        GhAccount account = ghAccountRepository.insert(1L, "work", tokenCipher.encrypt("sweep-token"),
                Set.of("repo"), Instant.now());
        fx.projectRepository().setGithubAccountId(fx.projectId(), account.id());
        ProjectConsoleWorktreeAndId console = createProjectConsoleWorktree(fx);
        WorktreeCleanupSweeper sweeper = sweeper(fx, List.of());

        List<String> removed = sweeper.sweep();

        assertThat(removed).containsExactly(console.worktreeId());
        assertThat(console.path()).doesNotExist();
    }

    @Test
    void discoveryIgnoresASameNamedDirectoryThatWasNeverRegisteredAsAWorktree(@TempDir Path tmp) throws Exception {
        // #339/ADR-104, per /t-review: discovery must ask git, not just match a
        // directory's name -- a same-named but unrelated directory (a manual backup,
        // a stray clone) must never be treated as a discovered project-console
        // worktree, only ever a real git worktree/t-work's `git worktree add`
        // actually registered.
        Fixture fx = fixture(tmp);
        Path phantom = fx.projectRoot()
                .resolveSibling(WorktreeCreationService.repoName(fx.projectRoot()) + "-console-deadbeef");
        Files.createDirectories(phantom);
        Files.writeString(phantom.resolve("not-a-worktree.txt"), "just a directory with the right name");

        assertThat(sweeper(fx, List.of()).allProjectConsoleWorktrees()).isEmpty();
    }

    private record WorktreeAndId(String worktreeId, Path path) {
    }

    private record ProjectConsoleWorktreeAndId(String worktreeId, Path path) {
    }

    /** A project-console-shaped sibling worktree (#339), detached at origin/main. */
    private static ProjectConsoleWorktreeAndId createProjectConsoleWorktree(Fixture fx)
            throws IOException, InterruptedException {
        return createProjectConsoleWorktree(fx, "origin/main");
    }

    /** Same as {@link #createProjectConsoleWorktree(Fixture)}, detached at {@code trunkRef} instead (#583). */
    private static ProjectConsoleWorktreeAndId createProjectConsoleWorktree(Fixture fx, String trunkRef)
            throws IOException, InterruptedException {
        String suffix = "abcd1234";
        Path worktreePath =
                fx.projectRoot().resolveSibling(WorktreeCreationService.repoName(fx.projectRoot()) + "-console-" + suffix);
        WorktreeCreationService.createDetachedWorktree(worktreePath, fx.projectRoot(), trunkRef, GitCredential.NONE);
        return new ProjectConsoleWorktreeAndId(fx.projectId + "-console-" + suffix, worktreePath);
    }

    /**
     * Checks out {@code branch} from the worktree's current (detached-at-origin/main)
     * HEAD and adds one real commit on it — a branch with actual, genuine content,
     * unlike a bare {@code checkout -b} whose tip would be trivially identical to
     * {@code origin/main} and therefore already "landed" under #554/ADR-107's own
     * literal-ancestor test.
     */
    private static void checkoutBranchWithRealCommit(Path worktreePath, String branch, String fileName,
            String content) throws IOException, InterruptedException {
        run(worktreePath, "git", "checkout", "-b", branch);
        Files.writeString(worktreePath.resolve(fileName), content);
        run(worktreePath, "git", "add", fileName);
        run(worktreePath, "git", "commit", "-m", "add " + fileName);
    }

    private record Fixture(Path projectRoot, long projectId, WorktreeSessionRepository repository,
            ProjectRepository projectRepository) {
    }

    private static Fixture fixture(Path tmp) throws IOException, InterruptedException {
        return fixture(tmp, "main");
    }

    /** Same as {@link #fixture(Path)}, on a repo whose trunk is {@code defaultBranch} instead of {@code main} (#583). */
    private static Fixture fixture(Path tmp, String defaultBranch) throws IOException, InterruptedException {
        Path projectRoot = initTestRepo(tmp, defaultBranch);
        WorktreeSessionRepository repository = TestSqliteDatabases.newRepository(tmp);
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        long projectId =
                projectRepository.createReady("proj", projectRoot.toString(), projectRoot, defaultBranch, 1L, Instant.now())
                        .id();
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
        Path worktreePath = Path.of(started.workingDirectory());
        // #340: opening a console no longer mints a branch itself -- startSession now
        // leaves the worktree detached at origin/main. These tests are specifically
        // about the fate of a worktree's *branch* on cleanup (#342), so simulate the
        // /t-work step that would normally follow: check out the real
        // wip/<id>-<slug> branch a worktree carries once implementation has started.
        run(worktreePath, "git", "checkout", "-b", "wip/" + issueNumber + "-" + WorktreeCreationService.slug(title));
        return new WorktreeAndId(started.worktreeId(), worktreePath);
    }

    private static WorktreeCleanupSweeper sweeper(Fixture fx, List<GhIssue> issues) {
        return sweeper(fx, new SessionRegistry(fx.repository), issues);
    }

    private static WorktreeCleanupSweeper sweeper(Fixture fx, SessionRegistry sessionRegistry, List<GhIssue> issues) {
        IssueWorktreeService worktreeService =
                new IssueWorktreeService(fx.repository, TestSqliteDatabases.newNoopAuthorization());
        ProjectGhResources ghResources =
                new ProjectGhResources(fx.projectRepository, ghAccountRepository(), tokenCipher(),
                (path, token) -> new FixedGhClient(issues));
        return new WorktreeCleanupSweeper(worktreeService, fx.projectRepository, ghResources, sessionRegistry,
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

    /** A minimal local repo with an "origin" remote and a {@code main} trunk — no network. */
    private static Path initTestRepo(Path dir) throws IOException, InterruptedException {
        return initTestRepo(dir, "main");
    }

    /** Same as {@link #initTestRepo(Path)}, on {@code trunk} instead of {@code main} (#583). */
    private static Path initTestRepo(Path dir, String trunk) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path bare = dir.resolve("origin.git");
        Path work = dir.resolve("work");
        Files.createDirectories(work);

        run(dir, "git", "init", "--bare", "-b", trunk, bare.toString());
        run(dir, "git", "init", "-b", trunk, work.toString());
        run(work, "git", "config", "user.email", "test@example.com");
        run(work, "git", "config", "user.name", "Test");
        Files.writeString(work.resolve("README.md"), "test repo");
        run(work, "git", "add", "README.md");
        run(work, "git", "commit", "-m", "initial commit");
        run(work, "git", "remote", "add", "origin", bare.toString());
        run(work, "git", "push", "origin", trunk);
        run(work, "git", "branch", "--set-upstream-to=origin/" + trunk, trunk);
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

    /** The branch checked out in {@code worktreePath}, while it still exists on disk. */
    private static String currentBranch(Path worktreePath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(worktreePath.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Command failed (" + exit + "): git rev-parse --abbrev-ref HEAD\n" + output);
        }
        return output;
    }

    /** Whether {@code branch} still exists in the repo rooted at {@code repoRoot} (main checkout or a worktree). */
    private static boolean branchExists(Path repoRoot, String branch) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + branch)
                .directory(repoRoot.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor() == 0;
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
