package dev.locklane.engine.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.template.ProjectTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

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

    // #546: a failing `git clone` must be diagnosable from the log alone.

    @Test
    void aFailedCloneLogsAWarnContainingGitsOwnStderr(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        List<ILoggingEvent> events = capturingLogs(() -> service.createProject("/does/not/exist", "broken", 1L));

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("git clone failed")
                    .containsIgnoringCase("does/not/exist");
        });
    }

    @Test
    void anExceptionDuringImportLogsWithTheExceptionAttached(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);
        // The workarea's own parent is reserved as a plain file, so
        // Files.createDirectories(project.workareaPath().getParent()) throws IOException
        // instead of the clone ever running -- clone()'s own catch block is what must
        // log this, not any WARN further down the happy path.
        Path ownerRoot = tmp.resolve("workarea").resolve("1");
        Files.createDirectories(ownerRoot.getParent());
        Files.writeString(ownerRoot, "not a directory");

        List<ILoggingEvent> events =
                capturingLogs(() -> service.createProject(origin.toString(), "blocked", 1L));

        ProjectRecord found = repositoryOver(tmp).findAll().stream()
                .filter(p -> p.name().equals("blocked")).findFirst().orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void aSuccessfulImportLogsTheStartAndReadyInfoLines(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "trunk");
        ProjectCheckoutService service = service(tmp);

        List<ILoggingEvent> events = capturingLogs(() -> service.createProject(origin.toString(), "myproj", 1L));

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("Importing project").contains(origin.toString())
                    .contains("default");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("ready on branch").contains("trunk");
        });
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

    // #525: the t-workflow installer's contract is to create the project at
    // <cwd>/<name> -- a subdirectory of wherever it runs, refusing when that path
    // already exists -- never at the working directory itself. The engine must
    // therefore run it somewhere scratch and move the produced tree to the workarea
    // root (whose slugged directory name can differ from the raw project name). This
    // stub honours that contract without the network fetch the real command does; it
    // receives the same $1 (installer URL) / $2 (project name) arguments.

    private static final String STUB_INSTALLER = """
            set -e
            [ ! -e "$2" ]
            git init --quiet -b main "$2"
            echo hello > "$2/README.md"
            git -C "$2" add -A
            git -C "$2" commit --quiet -m "Bootstrap"
            """;

    @Test
    void setUpLocalRepoAndPushWithBootstrapBuildsTheCheckoutAtTheWorkareaRoot(@TempDir Path tmp) throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        ProjectCheckoutService service = serviceWithInstallCommand(tmp, STUB_INSTALLER);
        ProjectRepository repository = repositoryOver(tmp);
        // The raw name ("Boot.Project") deliberately differs from the workarea
        // directory name ("boot-project"), the way createNewProject's slugging makes
        // them differ -- the installer builds under the raw name and the engine must
        // still land the tree at the reserved workarea path.
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("boot-project");
        ProjectRecord project =
                repository.create("Boot.Project", bareRemote.toString(), workarea, 1L, Instant.now());

        service.setUpLocalRepoAndPush(project, true);

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(found.defaultBranch()).isEqualTo("main");
        assertThat(workarea.resolve(".git")).isDirectory();
        assertThat(workarea.resolve("README.md")).exists();
        // The bootstrap commit was made under the engine-supplied identity -- proof the
        // installer ran with GIT_AUTHOR_*/GIT_COMMITTER_* set, so a host whose git has
        // no global identity can still make the first commit.
        assertThat(run(workarea, "git", "log", "-1", "--format=%an %ae").strip())
                .isEqualTo("locklane locklane@local");
        // The commit reached the remote.
        assertThat(run(bareRemote, "git", "log", "-1", "--format=%s", "main").strip()).isEqualTo("Bootstrap");
        // No scratch directory is left behind next to the workarea.
        try (var siblings = Files.list(workarea.getParent())) {
            assertThat(siblings).containsExactly(workarea);
        }
    }

    // #536: a chosen template's body is committed as PROJECT_TEMPLATE.md before the
    // push -- inside the initial commit on the plain path, as one extra commit on top
    // of the installer's tree on the bootstrap path -- and the pushed branch carries it.

    private static final ProjectTemplate TEMPLATE =
            new ProjectTemplate("node-server", "Node server", "Express", "# Node server\n\nBuild it.\n");

    @Test
    void createNewProjectRecordsTheTemplateNameOnTheRow(@TempDir Path tmp) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        ProjectCheckoutService service = new ProjectCheckoutService(repositoryOver(tmp),
                tmp.resolve("workarea").toString(), command -> { /* never run -- would shell out to gh for real */ },
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp));

        ProjectRecord withTemplate = service.createNewProject("my-org", "templated", false, 1L, null, TEMPLATE);
        ProjectRecord without = service.createNewProject("my-org", "plain", false, 1L, null, null);

        assertThat(withTemplate.template()).isEqualTo("node-server");
        assertThat(repositoryOver(tmp).findById(withTemplate.id()).orElseThrow().template()).isEqualTo("node-server");
        assertThat(without.template()).isNull();
    }

    @Test
    void setUpLocalRepoAndPushWithoutBootstrapCommitsTheTemplateInTheInitialCommit(@TempDir Path tmp)
            throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("templated");
        ProjectRecord project = repository.create("templated", bareRemote.toString(), workarea, 1L, Instant.now(),
                TEMPLATE.name());

        service.setUpLocalRepoAndPush(project, false, Map.of(), Optional.of(TEMPLATE));

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(Files.readString(workarea.resolve("PROJECT_TEMPLATE.md"))).isEqualTo(TEMPLATE.body());
        // One commit only -- the template rode in the initial commit -- and the pushed
        // branch's tree carries the file with the body text.
        assertThat(run(bareRemote, "git", "rev-list", "--count", found.defaultBranch()).strip()).isEqualTo("1");
        assertThat(run(bareRemote, "git", "show", found.defaultBranch() + ":PROJECT_TEMPLATE.md"))
                .isEqualTo(TEMPLATE.body());
        assertThat(run(bareRemote, "git", "show", found.defaultBranch() + ":README.md")).contains("templated");
    }

    @Test
    void setUpLocalRepoAndPushWithBootstrapAddsOneTemplateCommitOnTopOfTheInstallersTree(@TempDir Path tmp)
            throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        ProjectCheckoutService service = serviceWithInstallCommand(tmp, STUB_INSTALLER);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("boot-templated");
        ProjectRecord project = repository.create("boot-templated", bareRemote.toString(), workarea, 1L,
                Instant.now(), TEMPLATE.name());

        service.setUpLocalRepoAndPush(project, true, Map.of(), Optional.of(TEMPLATE));

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        // The installer's own commit is untouched underneath; the template is one extra
        // commit above it, made under the same engine identity the installer ran with.
        assertThat(run(bareRemote, "git", "log", "--format=%s", "main").strip().lines().toList())
                .containsExactly("Add project template", "Bootstrap");
        assertThat(run(workarea, "git", "log", "-1", "--format=%an %ae").strip()).isEqualTo("locklane locklane@local");
        assertThat(run(bareRemote, "git", "show", "main:PROJECT_TEMPLATE.md")).isEqualTo(TEMPLATE.body());
        assertThat(run(bareRemote, "git", "show", "main:README.md")).isEqualTo("hello\n");
    }

    @Test
    void setUpLocalRepoAndPushWithoutATemplateWritesNoTemplateFile(@TempDir Path tmp) throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("plain");
        ProjectRecord project = repository.create("plain", bareRemote.toString(), workarea, 1L, Instant.now());

        service.setUpLocalRepoAndPush(project, false, Map.of(), Optional.empty());

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(workarea.resolve("PROJECT_TEMPLATE.md")).doesNotExist();
        assertThat(run(bareRemote, "git", "ls-tree", "--name-only", found.defaultBranch()).strip())
                .isEqualTo("README.md");
    }

    @Test
    void setUpLocalRepoAndPushWithBootstrapMarksFailedWhenTheInstallerFails(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = serviceWithInstallCommand(tmp, "echo boom >&2; exit 7");
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("boot-fail");
        ProjectRecord project =
                repository.create("boot-fail", "https://127.0.0.1:1/org/boot-fail.git", workarea, 1L, Instant.now());

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.setUpLocalRepoAndPush(project, true);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("t-workflow install failed").contains("boom");
        });
        assertThat(workarea).doesNotExist();
        try (var siblings = Files.list(workarea.getParent())) {
            assertThat(siblings).isEmpty();
        }
    }

    @Test
    void setUpLocalRepoAndPushWithBootstrapMarksFailedWhenNoCheckoutIsProduced(@TempDir Path tmp) throws Exception {
        // Exits 0 without building anything -- a contract violation (the shape of the
        // #525 defect itself) must fail loudly, not surface as a push error later.
        ProjectCheckoutService service = serviceWithInstallCommand(tmp, "true");
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("boot-empty");
        ProjectRecord project = repository.create(
                "boot-empty", "https://127.0.0.1:1/org/boot-empty.git", workarea, 1L, Instant.now());

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.setUpLocalRepoAndPush(project, true);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("did not produce a git checkout");
        });
        assertThat(workarea).doesNotExist();
        try (var siblings = Files.list(workarea.getParent())) {
            assertThat(siblings).isEmpty();
        }
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

    // #546: the token embedded in the push URL above must never reach a log line,
    // even on a failing push that used one.

    @Test
    void aFailingPushWithAStoredTokenNeverLogsTheTokenItself(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("token-project");
        ProjectRecord project = repository.create(
                "token-project", "https://127.0.0.1:1/org/token-project.git", workarea, 1L, Instant.now());
        repository.setGithubToken(project.id(), tokenCipher(tmp).encrypt("secret-token"));

        List<ILoggingEvent> events = capturingLogs(() -> {
            try {
                service.setUpLocalRepoAndPush(project, false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(events).isNotEmpty();
        assertThat(events).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("secret-token"));
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

    // #531: a t-workflow bootstrap's first push carries .github/workflows/ci.yml, which
    // GitHub refuses from a token without the `workflow` scope. Rather than let that
    // surface as a raw push rejection after the repo was created and the installer
    // ran, the engine checks the token's scopes first -- before `gh repo create`, so
    // nothing is left behind -- and fails with the one message an operator needs.
    //
    // These tests never reach the real `gh`: the scope lookup and the ambient token
    // are stubbed, and the end-to-end ones name an org that cannot exist and assert
    // exactly one WARN, so a gate that leaked through to `gh repo create` would fail
    // the test (a second WARN, from the 404 or from gh being absent) instead of
    // creating anything.

    private static final String NO_SUCH_ORG = "locklane-531-no-such-org";

    @Test
    void createNewProjectWithBootstrapFailsEarlyWhenTheTokenLacksTheWorkflowScope(@TempDir Path tmp) {
        Path installerRan = tmp.resolve("installer-ran");
        ProjectCheckoutService service = serviceWithScopes(tmp, Runnable::run, () -> Optional.of("gh-cli-token"),
                "touch " + installerRan, token -> Optional.of(Set.of("repo", "read:org")));

        List<ILoggingEvent> events = capturingLogs(
                () -> service.createNewProject(NO_SUCH_ORG, "scoped-out", true, 1L));

        ProjectRecord found = repositoryOver(tmp).findAll().get(0);
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        List<ILoggingEvent> warnings = events.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
                .contains("scoped-out")
                .contains(String.valueOf(found.id()))
                .contains("`workflow` scope")
                .contains("gh auth refresh -h github.com -s workflow");
        // Neither the installer nor any git step ran: no marker, no workarea.
        assertThat(installerRan).doesNotExist();
        assertThat(found.workareaPath()).doesNotExist();
    }

    @Test
    void createNewProjectWithBootstrapChecksTheStoredTokenTheSameWay(@TempDir Path tmp) {
        // The executor queues instead of running, so the per-project token can be
        // stored between createNewProject's synchronous part and the async work --
        // the way a project that already has a token stored would look.
        List<Runnable> queued = new ArrayList<>();
        List<String> lookedUp = new ArrayList<>();
        ProjectCheckoutService service = serviceWithScopes(tmp, queued::add, () -> Optional.of("gh-cli-token"),
                "true", token -> {
                    lookedUp.add(token);
                    return Optional.of(Set.of("repo"));
                });
        ProjectRepository repository = repositoryOver(tmp);

        ProjectRecord project = service.createNewProject(NO_SUCH_ORG, "stored-scoped-out", true, 1L);
        repository.setGithubToken(project.id(), tokenCipher(tmp).encrypt("stored-token"));
        List<ILoggingEvent> events = capturingLogs(() -> queued.forEach(Runnable::run));

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        // The stored token is what the push would use, so it is what gets checked --
        // never the ambient gh login behind it.
        assertThat(lookedUp).containsExactly("stored-token");
        List<ILoggingEvent> warnings = events.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
                .contains("stored per-project token")
                .contains("gh auth refresh -h github.com -s workflow");
    }

    @Test
    void tokenCanPushWorkflowsWhenTheReportedScopesIncludeWorkflow(@TempDir Path tmp) {
        ProjectCheckoutService service = serviceWithScopes(tmp, Runnable::run, () -> Optional.of("gh-cli-token"),
                "true", token -> Optional.of(Set.of("repo", "workflow")));
        ProjectRecord project = newProjectRecord(tmp, "has-workflow");

        assertThat(service.tokenCanPushWorkflows(project)).isTrue();
    }

    @Test
    void tokenCanPushWorkflowsFailsOpenWhenTheScopesCannotBeDetermined(@TempDir Path tmp) {
        // A fine-grained PAT or a GitHub App token reports no classic scopes at all,
        // and `gh api` itself can fail -- neither may block a bootstrap that would have
        // succeeded before this check existed. Likewise with no token at all: that is
        // the existing "No GitHub credentials available" path's call, not this one's.
        ProjectCheckoutService unknownScopes = serviceWithScopes(tmp, Runnable::run,
                () -> Optional.of("fine-grained-token"), "true", token -> Optional.empty());
        ProjectCheckoutService noToken = serviceWithScopes(tmp, Runnable::run, Optional::empty, "true",
                token -> fail("no token to look up"));
        ProjectRecord project = newProjectRecord(tmp, "unknown-scopes");

        assertThat(unknownScopes.tokenCanPushWorkflows(project)).isTrue();
        assertThat(noToken.tokenCanPushWorkflows(project)).isTrue();
    }

    @Test
    void setUpLocalRepoAndPushWithoutBootstrapNeverConsultsTheScopeLookup(@TempDir Path tmp) throws Exception {
        // A plain project has no workflow file to push, so a token with only `repo`
        // is all it needs -- the scope lookup must not even be asked.
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        ProjectCheckoutService service = serviceWithScopes(tmp, Runnable::run, () -> Optional.of("gh-cli-token"),
                "true", token -> fail("the plain path must not check scopes"));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("plain-project");
        ProjectRecord project =
                repository.create("plain-project", bareRemote.toString(), workarea, 1L, Instant.now());

        service.setUpLocalRepoAndPush(project, false);

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        // Whatever branch name the host's `git init` chose, the commit reached the remote.
        assertThat(run(bareRemote, "git", "log", "-1", "--format=%s", found.defaultBranch()).strip())
                .isEqualTo("Initial commit");
    }

    // #532: a project may act as one of the accounts `gh` is logged into on the host.
    // The stub gh below stands in for the real CLI: `auth token --user <login>` knows
    // exactly one account ("work", token "work-token") and answers any other login
    // with gh 2.98.0's real wording and exit 1; `repo create` records the GH_TOKEN it
    // was given and its arguments. Every invocation is appended to a calls log.

    @Test
    void createProjectWithALoginStoresThatAccountsTokenBeforeCloning(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "imported", 1L, "work");

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(found.defaultBranch()).isEqualTo("main");
        String stored = repository.findGithubToken(project.id()).orElseThrow();
        assertThat(stored).isNotEqualTo("work-token"); // encrypted at rest
        assertThat(tokenCipher(tmp).decrypt(stored)).isEqualTo("work-token");
        assertThat(Files.readString(ghLog.resolve("calls"))).isEqualTo("auth token --user work\n");
        // The clone itself is untouched: no token in the remote URL, no gh involved.
        assertThat(run(project.workareaPath(), "git", "remote", "get-url", "origin").strip())
                .isEqualTo(origin.toString());
    }

    @Test
    void createProjectWithAnUnknownLoginFailsBeforeCloningAndNamesTheLogin(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ProjectRecord project;
        try {
            project = service.createProject(origin.toString(), "unknown-login", 1L, "nobody");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(repository.findGithubToken(project.id())).isEmpty();
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("'nobody'")
                    .contains("no oauth token found for github.com account nobody");
        });
        // No clone was attempted: the workarea was never created.
        assertThat(project.workareaPath()).doesNotExist();
        assertThat(Files.readString(ghLog.resolve("calls"))).isEqualTo("auth token --user nobody\n");
    }

    @Test
    void createProjectWithoutALoginNeverInvokesGh(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));

        ProjectRecord project = service.createProject(origin.toString(), "plain", 1L, "  ");

        assertThat(repositoryOver(tmp).findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.READY);
        assertThat(repositoryOver(tmp).findGithubToken(project.id())).isEmpty();
        assertThat(ghLog.resolve("calls")).doesNotExist();
    }

    @Test
    void createRepoAndPushWithALoginActsAsThatAccountThroughGhTokenAndStoresIt(@TempDir Path tmp) throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        // A pre-receive hook sees the pushing process's environment, so it can prove
        // the push itself ran with GH_TOKEN set -- a local bare repo can't otherwise
        // tell one pusher from another.
        Path hook = bareRemote.resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, "#!/usr/bin/env bash\nprintf '%s' \"${GH_TOKEN-<unset>}\" > \""
                + ghLog.resolve("push-env") + "\"\n");
        hook.toFile().setExecutable(true);
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("as-work");
        ProjectRecord project = repository.create("as-work", bareRemote.toString(), workarea, 1L, Instant.now());

        service.createRepoAndPush(project, "my-org", false, Optional.of("work"));

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(tokenCipher(tmp).decrypt(repository.findGithubToken(project.id()).orElseThrow()))
                .isEqualTo("work-token");
        assertThat(Files.readString(ghLog.resolve("calls")))
                .isEqualTo("auth token --user work\nrepo create my-org/as-work --private\n");
        assertThat(Files.readString(ghLog.resolve("repo-create-env"))).isEqualTo("work-token");
        assertThat(Files.readString(ghLog.resolve("push-env"))).isEqualTo("work-token");
        assertThat(run(bareRemote, "git", "log", "-1", "--format=%s", found.defaultBranch()).strip())
                .isEqualTo("Initial commit");
    }

    @Test
    void parseOauthScopesReadsTheHeaderAsGhApiPrintsIt() {
        String response = """
                HTTP/2.0 200 OK
                Content-Type: application/json; charset=utf-8
                X-Accepted-Oauth-Scopes:\s
                X-Oauth-Scopes: admin:public_key, gist, read:org, repo

                {"login":"someone"}
                X-Oauth-Scopes: workflow
                """;

        assertThat(ProjectCheckoutService.parseOauthScopes(response))
                .contains(Set.of("admin:public_key", "gist", "read:org", "repo"));
    }

    @Test
    void parseOauthScopesMatchesTheHeaderNameCaseInsensitively() {
        assertThat(ProjectCheckoutService.parseOauthScopes("HTTP/1.1 200 OK\r\nx-oauth-scopes: repo, workflow\r\n\r\n"))
                .contains(Set.of("repo", "workflow"));
    }

    @Test
    void parseOauthScopesIsEmptyWhenTheHeaderIsAbsentOrBlank() {
        // Absent or blank means "this token has no classic scopes to report" (a
        // fine-grained PAT, an App token) -- unknown, which the gate must not treat as
        // "lacks workflow".
        assertThat(ProjectCheckoutService.parseOauthScopes("HTTP/2.0 200 OK\nContent-Type: text/plain\n\nbody"))
                .isEmpty();
        assertThat(ProjectCheckoutService.parseOauthScopes("HTTP/2.0 200 OK\nX-Oauth-Scopes: \n\n")).isEmpty();
        assertThat(ProjectCheckoutService.parseOauthScopes("")).isEmpty();
    }

    @Test
    void createRepoAndPushWithAnUnknownLoginCreatesNothing(@TempDir Path tmp) throws Exception {
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("as-nobody");
        ProjectRecord project = repository.create(
                "as-nobody", "https://127.0.0.1:1/my-org/as-nobody.git", workarea, 1L, Instant.now());

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.createRepoAndPush(project, "my-org", false, Optional.of("nobody"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(repository.findGithubToken(project.id())).isEmpty();
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("'nobody'");
        });
        // gh was asked for the token and nothing else -- no repository was created.
        assertThat(Files.readString(ghLog.resolve("calls"))).isEqualTo("auth token --user nobody\n");
        assertThat(ghLog.resolve("repo-create-env")).doesNotExist();
        assertThat(workarea).doesNotExist();
    }

    // #531 composes with #532: the workflow-scope gate examines the token the bootstrap
    // push will actually use -- with a chosen account, that account's token, stored on
    // the row before the gate runs -- not the host's active login.

    @Test
    void createRepoAndPushRunsTheWorkflowScopeGateOnTheChosenAccountsToken(@TempDir Path tmp) throws Exception {
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog),
                token -> token.equals("work-token") ? Optional.of(Set.of("repo")) : Optional.of(Set.of("repo", "workflow")));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("as-work-no-scope");
        ProjectRecord project = repository.create(
                "as-work-no-scope", "https://127.0.0.1:1/my-org/as-work-no-scope.git", workarea, 1L, Instant.now());

        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.createRepoAndPush(project, "my-org", true, Optional.of("work"));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        // The chosen account's token was stored, and it is the one the gate judged.
        assertThat(tokenCipher(tmp).decrypt(repository.findGithubToken(project.id()).orElseThrow()))
                .isEqualTo("work-token");
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("the stored per-project token")
                    .contains(ProjectCheckoutService.WORKFLOW_SCOPE);
        });
        // Refused before `gh repo create`: nothing was created on GitHub or on disk.
        assertThat(Files.readString(ghLog.resolve("calls"))).isEqualTo("auth token --user work\n");
        assertThat(ghLog.resolve("repo-create-env")).doesNotExist();
        assertThat(workarea).doesNotExist();
    }

    @Test
    void createRepoAndPushWithoutALoginRunsGhAsTheHostsActiveAccount(@TempDir Path tmp) throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("ambient");
        ProjectRecord project = repository.create("ambient", bareRemote.toString(), workarea, 1L, Instant.now());

        service.createRepoAndPush(project, "my-org", false, Optional.empty());

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.READY);
        assertThat(repository.findGithubToken(project.id())).isEmpty();
        assertThat(Files.readString(ghLog.resolve("calls"))).isEqualTo("repo create my-org/ambient --private\n");
        // No GH_TOKEN was injected: gh ran as whatever the host has active, as before #532.
        assertThat(Files.readString(ghLog.resolve("repo-create-env"))).isEqualTo("<unset>");
    }

    /**
     * A stub {@code gh} for #532's tests (shared with {@code ProjectControllerTest}):
     * knows one account, {@code work} → {@code work-token}; records every invocation
     * in {@code <log>/calls}, and {@code repo create}'s {@code GH_TOKEN} in
     * {@code <log>/repo-create-env}. Returns the script's absolute path.
     */
    static String stubGh(Path tmp, Path log) throws IOException {
        Path script = tmp.resolve("stub-gh");
        Files.writeString(script, """
                #!/usr/bin/env bash
                log=%s
                printf '%%s\\n' "$*" >> "$log/calls"
                case "$1 $2" in
                  "auth token")
                    if [ "$3" = "--user" ] && [ "$4" = "work" ]; then
                      echo work-token
                    else
                      echo "no oauth token found for github.com account $4" >&2
                      exit 1
                    fi
                    ;;
                  "repo create")
                    printf '%%s' "${GH_TOKEN-<unset>}" > "$log/repo-create-env"
                    ;;
                  *)
                    echo "stub gh: unexpected invocation: $*" >&2
                    exit 2
                    ;;
                esac
                """.formatted(log));
        script.toFile().setExecutable(true);
        return script.toString();
    }

    /**
     * Like {@link #service(Path)}, but with {@code ghExecutable} standing in for the real
     * gh (#532); the #531 scope lookup reports "unknown", which that gate lets through.
     */
    private static ProjectCheckoutService serviceWithStubGh(Path tmp, String ghExecutable) {
        return serviceWithStubGh(tmp, ghExecutable, token -> Optional.empty());
    }

    /** Same, with the #531 scope lookup substituted too, so both gates can be exercised on one token. */
    private static ProjectCheckoutService serviceWithStubGh(Path tmp, String ghExecutable,
            Function<String, Optional<Set<String>>> tokenScopes) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                Optional::empty, "exit 1", tokenScopes, ghExecutable);
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

    /** Like {@link #service(Path)}, but substituting the t-workflow install command (#525) — see STUB_INSTALLER. */
    private static ProjectCheckoutService serviceWithInstallCommand(Path tmp, String installCommand) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                Optional::empty, installCommand);
    }

    /** Like {@link #service(Path)}, with every collaborator the #531 scope gate touches substituted. */
    private static ProjectCheckoutService serviceWithScopes(Path tmp, Executor executor,
            Supplier<Optional<String>> ambientToken, String installCommand,
            Function<String, Optional<Set<String>>> tokenScopes) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), executor,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                ambientToken, installCommand, tokenScopes);
    }

    private static ProjectRecord newProjectRecord(Path tmp, String name) {
        return repositoryOver(tmp).create(name, "https://github.com/" + NO_SUCH_ORG + "/" + name + ".git",
                tmp.resolve("workarea").resolve("1").resolve(name), 1L, Instant.now());
    }

    /** Everything {@code ProjectCheckoutService} logged while {@code action} ran. */
    private static List<ILoggingEvent> capturingLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ProjectCheckoutService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
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
