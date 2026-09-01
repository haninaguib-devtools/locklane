package dev.locklane.engine.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises real {@code git clone} against a throwaway local repository (no
 * network) for genuine confidence (#42, mirroring {@code WorktreeCreationServiceTest}
 * for #20). {@code cloneExecutor} runs same-thread ({@code Runnable::run}) so every
 * assertion below sees the clone's outcome without polling.
 */
class ProjectCheckoutServiceTest {

    @Test
    void deriveNameTakesTheLastPathSegmentAndDropsDotGit() {
        assertThat(ProjectCheckoutService.deriveName("https://github.com/foo/bar.git")).isEqualTo("bar");
        assertThat(ProjectCheckoutService.deriveName("git@github.com:foo/bar.git")).isEqualTo("bar");
        assertThat(ProjectCheckoutService.deriveName("https://example.com/repo/")).isEqualTo("repo");
    }

    @Test
    void slugLowercasesAndDashesNonAlnumRuns() {
        assertThat(ProjectCheckoutService.slug("My Cool Project!")).isEqualTo("my-cool-project");
        assertThat(ProjectCheckoutService.slug("---")).isEqualTo("project");
    }

    @Test
    void createsARealCloneAndDiscoversTheActualDefaultBranch(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "trunk");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "myproj", 1L);

        assertThat(project.name()).isEqualTo("myproj");
        assertThat(project.ownerUserId()).isEqualTo(1L);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("myproj");
        assertThat(workarea).isDirectory();
        assertThat(project.workareaPath()).isEqualTo(workarea);

        ProjectRepository repository = repositoryOver(tmp);
        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(found.defaultBranch()).isEqualTo("trunk");
    }

    @Test
    void aBlankNameIsDerivedFromTheGitUrl(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "  ", 1L);

        assertThat(project.name()).isEqualTo(ProjectCheckoutService.deriveName(origin.toString()));
    }

    @Test
    void aFailedCloneMarksTheProjectFailedAndLeavesTheGitUrlIntact(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject("/does/not/exist", "broken", 1L);

        ProjectRepository repository = repositoryOver(tmp);
        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(found.defaultBranch()).isNull();
        assertThat(found.gitUrl()).isEqualTo("/does/not/exist");
    }

    @Test
    void aNameCollisionGetsANumericSuffix(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord first = service.createProject(origin.toString(), "dup", 1L);
        ProjectRecord second = service.createProject(origin.toString(), "dup", 1L);

        assertThat(first.workareaPath()).isNotEqualTo(second.workareaPath());
        assertThat(second.workareaPath().getFileName().toString()).isEqualTo("dup-2");
    }

    @Test
    void twoDifferentOwnersCanEachHaveAProjectOfTheSameSlug(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord ownedByOne = service.createProject(origin.toString(), "shared-name", 1L);
        ProjectRecord ownedByTwo = service.createProject(origin.toString(), "shared-name", 2L);

        assertThat(ownedByOne.workareaPath().getFileName().toString()).isEqualTo("shared-name");
        assertThat(ownedByTwo.workareaPath().getFileName().toString()).isEqualTo("shared-name");
        assertThat(ownedByOne.workareaPath()).isNotEqualTo(ownedByTwo.workareaPath());
        assertThat(ownedByOne.workareaPath()).isDirectory();
        assertThat(ownedByTwo.workareaPath()).isDirectory();
    }

    @Test
    void retryReClonesAFailedProject(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRecord failed = service.createProject("/does/not/exist", "will-retry", 1L);
        assertThat(repositoryOver(tmp).findById(failed.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);

        // Point retry at a real repo isn't possible without changing the stored git
        // URL (out of scope), so this covers what retry can control: it re-runs the
        // clone against the same (still-broken) URL and the project stays FAILED,
        // not stuck CLONING forever.
        Optional<ProjectRecord> retried = service.retry(failed.id());

        assertThat(retried).isPresent();
        assertThat(repositoryOver(tmp).findById(failed.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
    }

    @Test
    void retryOnAReadyProjectIsEmpty(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);
        ProjectRecord ready = service.createProject(origin.toString(), "already-ready", 1L);

        assertThat(service.retry(ready.id())).isEmpty();
    }

    @Test
    void retryOnAnUnknownProjectIsEmpty(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        assertThat(service.retry(999)).isEmpty();
    }

    @Test
    void deleteRemovesTheProjectAndItsWorkareaDirectory(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);
        ProjectRecord project = service.createProject(origin.toString(), "to-delete", 1L);
        assertThat(project.workareaPath()).isDirectory();

        ProjectCheckoutService.DeleteOutcome outcome = service.delete(project.id());

        assertThat(outcome).isEqualTo(ProjectCheckoutService.DeleteOutcome.DELETED);
        assertThat(repositoryOver(tmp).findById(project.id())).isEmpty();
        assertThat(project.workareaPath()).doesNotExist();
    }

    @Test
    void deletingAnUnknownProjectIsNotFound(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        assertThat(service.delete(999)).isEqualTo(ProjectCheckoutService.DeleteOutcome.NOT_FOUND);
    }

    @Test
    void deleteRefusesAProjectWithAnOpenWorktreeOrConsole(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        IssueWorktreeService worktreeService = new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization());
        ProjectCheckoutService service = new ProjectCheckoutService(repositoryOver(tmp),
                tmp.resolve("workarea").toString(), Runnable::run, worktreeService, tokenCipher(tmp));
        ProjectRecord project = service.createProject(origin.toString(), "still-open", 1L);
        sessions.recordAttach(project.id() + "-174-rename-toggle", tmp.resolve("wt"), Instant.now(), "alice");

        ProjectCheckoutService.DeleteOutcome outcome = service.delete(project.id());

        assertThat(outcome).isEqualTo(ProjectCheckoutService.DeleteOutcome.HAS_OPEN_SESSIONS);
        assertThat(repositoryOver(tmp).findById(project.id())).isPresent();
        assertThat(project.workareaPath()).isDirectory();
    }

    @Test
    void forceDeleteRemovesAnOpenSessionsWorkareaAndDbRowUnlikeDelete(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        IssueWorktreeService issueWorktreeService = new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization());
        ProjectCheckoutService service = new ProjectCheckoutService(repositoryOver(tmp),
                tmp.resolve("workarea").toString(), Runnable::run, issueWorktreeService, tokenCipher(tmp));
        ProjectRecord project = service.createProject(origin.toString(), "force-delete-me", 1L);
        sessions.recordAttach(project.id() + "-174-rename-toggle", tmp.resolve("wt"), Instant.now(), "alice");

        // #240's cascade-delete: unlike delete(), forceDelete() never refuses on an
        // open session -- it removes the session too.
        service.forceDelete(project.id());

        assertThat(repositoryOver(tmp).findById(project.id())).isEmpty();
        assertThat(project.workareaPath()).doesNotExist();
        assertThat(issueWorktreeService.hasAnySessions(project.id())).isFalse();
    }

    @Test
    void forceDeleteOnAnUnknownProjectIsANoOp(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        service.forceDelete(999);

        assertThat(repositoryOver(tmp).findById(999)).isEmpty();
    }

    // #491's "create new" flow. createNewProject/createRepoAndPush themselves run
    // `gh repo create` for real -- never exercised here, since that would be a genuine
    // network call regardless of authentication (the record notes it as manually
    // checked instead). What's covered below: createNewProject's synchronous part
    // (never letting its async task run at all, so `gh` is never invoked), and
    // setUpLocalRepoAndPush -- everything *after* the GitHub repo exists -- against a
    // throwaway local bare repo standing in for it, exactly like the import tests
    // above use one to stand in for an existing GitHub remote.

    @Test
    void createNewProjectPersistsCloningWithADerivedGitUrlAndWorkareaPath(@TempDir Path tmp) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        ProjectCheckoutService service = new ProjectCheckoutService(repositoryOver(tmp),
                tmp.resolve("workarea").toString(), command -> { /* never run -- would shell out to gh for real */ },
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp));

        ProjectRecord project = service.createNewProject("my-org", "my-project", false, 1L);

        assertThat(project.name()).isEqualTo("my-project");
        assertThat(project.gitUrl()).isEqualTo("https://github.com/my-org/my-project.git");
        assertThat(project.ownerUserId()).isEqualTo(1L);
        assertThat(project.status()).isEqualTo(ProjectStatus.CLONING);
        assertThat(project.workareaPath())
                .isEqualTo(tmp.resolve("workarea").resolve("1").resolve("my-project"));
    }

    @Test
    void setUpLocalRepoAndPushWithoutBootstrapInitsCommitsAndPushes(@TempDir Path tmp) throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("new-project");
        ProjectRecord project =
                repository.create("new-project", bareRemote.toString(), workarea, 1L, Instant.now());

        service.setUpLocalRepoAndPush(project, false);

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(found.defaultBranch()).isNotBlank();
        assertThat(workarea.resolve("README.md")).exists();
        assertThat(Files.readString(workarea.resolve("README.md"))).contains("new-project");
    }

    @Test
    void setUpLocalRepoAndPushMarksFailedWhenThePushFails(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("broken-project");
        ProjectRecord project =
                repository.create("broken-project", "/does/not/exist", workarea, 1L, Instant.now());

        service.setUpLocalRepoAndPush(project, false);

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
    }

    // #505: a headless push can't fall back on interactive credential prompting, so a
    // user whose only GitHub credential is SSH-based (no HTTPS credential helper on the
    // host) needs the push itself to authenticate — with the per-project token already
    // stored for the project, over HTTPS (the non-goal that rules out an SSH remote).

    @Test
    void setUpLocalRepoAndPushAuthenticatesWithTheStoredGithubTokenWhenPresent(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("token-project");
        // A host+port nothing listens on: `git remote add` (no network) still succeeds,
        // and the subsequent `git push` fails fast (connection refused) rather than
        // hanging — real network access isn't needed to prove the token was wired in.
        ProjectRecord project = repository.create(
                "token-project", "https://127.0.0.1:1/org/token-project.git", workarea, 1L, Instant.now());
        repository.setGithubToken(project.id(), tokenCipher(tmp).encrypt("secret-token"));

        service.setUpLocalRepoAndPush(project, false);

        String configuredUrl = run(workarea, "git", "remote", "get-url", "origin").strip();
        assertThat(configuredUrl)
                .isEqualTo("https://x-access-token:secret-token@127.0.0.1:1/org/token-project.git");
        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(found.gitUrl()).isEqualTo("https://127.0.0.1:1/org/token-project.git");
    }

    // #513: with no per-project token stored yet (a freshly created project), the push
    // falls back to whatever identity `gh` is already logged in as on this host --
    // exactly the identity that just created the repository -- instead of going out
    // unauthenticated and hitting git's opaque interactive-prompt failure.

    @Test
    void setUpLocalRepoAndPushFallsBackToGhAuthTokenWhenNoStoredToken(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = serviceWithAmbientToken(tmp, () -> Optional.of("gh-cli-token"));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("ambient-token-project");
        ProjectRecord project = repository.create(
                "ambient-token-project", "https://127.0.0.1:1/org/ambient-token-project.git", workarea, 1L,
                Instant.now());

        service.setUpLocalRepoAndPush(project, false);

        String configuredUrl = run(workarea, "git", "remote", "get-url", "origin").strip();
        assertThat(configuredUrl)
                .isEqualTo("https://x-access-token:gh-cli-token@127.0.0.1:1/org/ambient-token-project.git");
    }

    @Test
    void setUpLocalRepoAndPushFailsClearlyWithNoCredentialsAtAll(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = serviceWithAmbientToken(tmp, Optional::empty);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("no-credentials-project");
        ProjectRecord project = repository.create(
                "no-credentials-project", "https://127.0.0.1:1/org/no-credentials-project.git", workarea, 1L,
                Instant.now());

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.setUpLocalRepoAndPush(project, false);
        } finally {
            logger.detachAppender(appender);
        }

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("No GitHub credentials available");
        });
        // Bails before even configuring a remote -- no unauthenticated push is attempted.
        assertThat(Files.readString(workarea.resolve(".git").resolve("config")))
                .doesNotContain("[remote \"origin\"]");
    }

    @Test
    void setUpLocalRepoAndPushLogsTheCapturedOutputOnFailure(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("broken-project");
        ProjectRecord project =
                repository.create("broken-project", "/does/not/exist", workarea, 1L, Instant.now());

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.setUpLocalRepoAndPush(project, false);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("does not appear to be a git repository");
        });
    }

    private static ProjectCheckoutService service(Path tmp) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp));
    }

    /** Like {@link #service(Path)}, but with a fake stand-in for `gh auth token` (#513) instead of the real CLI. */
    private static ProjectCheckoutService serviceWithAmbientToken(Path tmp, Supplier<Optional<String>> ambientToken) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                ambientToken);
    }

    private static ProjectRepository repositoryOver(Path tmp) {
        return TestSqliteDatabases.newProjectRepository(tmp);
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A minimal local bare repo with a controllable default branch name — no network. */
    private static Path initBareOriginWithDefaultBranch(Path tmp, String defaultBranch)
            throws IOException, InterruptedException {
        Path bare = tmp.resolve("origin-" + defaultBranch + ".git");
        Path seed = tmp.resolve("seed-" + defaultBranch);
        Files.createDirectories(seed);

        run(tmp, "git", "init", "--bare", "-b", defaultBranch, bare.toString());
        run(tmp, "git", "init", "-b", defaultBranch, seed.toString());
        run(seed, "git", "config", "user.email", "test@example.com");
        run(seed, "git", "config", "user.name", "Test");
        Files.writeString(seed.resolve("README.md"), "seed");
        run(seed, "git", "add", "README.md");
        run(seed, "git", "commit", "-m", "initial commit");
        run(seed, "git", "remote", "add", "origin", bare.toString());
        run(seed, "git", "push", "origin", defaultBranch);
        return bare;
    }

    private static String run(Path cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
