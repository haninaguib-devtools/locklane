package dev.locklane.engine.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.locklane.engine.github.GhAccount;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
                    .contains("none chosen");
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
                tmp.resolve("workarea").toString(), Runnable::run, worktreeService, tokenCipher(tmp), ghAccounts(tmp));
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
                tmp.resolve("workarea").toString(), Runnable::run, issueWorktreeService, tokenCipher(tmp), ghAccounts(tmp));
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
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                ghAccounts(tmp));

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
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                ghAccounts(tmp));

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

        service.setUpLocalRepoAndPush(project, false, java.util.Map.of(), Optional.of(TEMPLATE));

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

        service.setUpLocalRepoAndPush(project, true, java.util.Map.of(), Optional.of(TEMPLATE));

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

        service.setUpLocalRepoAndPush(project, false, java.util.Map.of(), Optional.empty());

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
    // host) needs the push itself to authenticate — with the project's chosen
    // account's token (#550) already stored, over HTTPS (the non-goal that rules out
    // an SSH remote).

    @Test
    void setUpLocalRepoAndPushConfiguresACredentialHelperInsteadOfEmbeddingTheToken(@TempDir Path tmp)
            throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("token-project");
        // A host+port nothing listens on: `git remote add` (no network) still succeeds,
        // and the subsequent `git push` fails fast (connection refused) rather than
        // hanging — real network access isn't needed to prove the token was wired in.
        ProjectRecord project = repository.create(
                "token-project", "https://127.0.0.1:1/org/token-project.git", workarea, 1L, Instant.now());
        GhAccount account = seedAccount(tmp, 1L, "work", "secret-token", Set.of("repo"));
        repository.setGithubAccountId(project.id(), account.id());

        service.setUpLocalRepoAndPush(project, false);

        // #551: the plain HTTPS URL, no token embedded in it -- `git remote -v` in the
        // real UI would show exactly this.
        String configuredUrl = run(workarea, "git", "remote", "get-url", "origin").strip();
        assertThat(configuredUrl).isEqualTo("https://127.0.0.1:1/org/token-project.git");
        // The repo-local credential helper is configured either way (the push having
        // failed afterwards, for an unrelated reason -- nothing is listening on
        // 127.0.0.1:1 -- doesn't unwind this).
        assertThat(run(workarea, "git", "config", "--get", "credential.helper").strip())
                .isEqualTo(ProjectCheckoutService.CREDENTIAL_HELPER_SCRIPT);
        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(found.gitUrl()).isEqualTo("https://127.0.0.1:1/org/token-project.git");
    }

    // #546: the token embedded in the push URL above must never reach a log line,
    // even on a failing push that used one.

    @Test
    void aFailingPushWithAChosenAccountNeverLogsTheTokenItself(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("token-project");
        ProjectRecord project = repository.create(
                "token-project", "https://127.0.0.1:1/org/token-project.git", workarea, 1L, Instant.now());
        GhAccount account = seedAccount(tmp, 1L, "work", "secret-token", Set.of("repo"));
        repository.setGithubAccountId(project.id(), account.id());

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

    // #550: with no chosen account, there is no fallback of any kind (the old
    // #513/#532 "fall back to the host's own gh login" behaviour is gone) -- a push
    // that needs credentials and has none fails clearly.

    @Test
    void setUpLocalRepoAndPushFailsClearlyWithNoAccountChosen(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
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
            assertThat(event.getFormattedMessage()).contains("No GitHub credentials available")
                    .contains("no GitHub account chosen");
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

    // #550: a project may act as one of the accounts the caller has signed in to
    // Locklane. Each test below seeds a real GhAccountRepository row and references
    // it by id -- no `gh` stub is needed any more, since the engine no longer shells
    // out to look a token up: it is already stored, encrypted, on the account row.

    @Test
    void createProjectWithAnAccountStoresItsTokenBeforeCloning(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        GhAccount account = seedAccount(tmp, 1L, "work", "work-token", Set.of("repo"));
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "imported", 1L, account.id());

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(found.defaultBranch()).isEqualTo("main");
        assertThat(repository.findGithubAccountId(project.id())).contains(account.id());
        // No token in the remote URL: it clones as a plain local path, exactly as given.
        assertThat(run(project.workareaPath(), "git", "remote", "get-url", "origin").strip())
                .isEqualTo(origin.toString());
        // #551: the chosen account's credential helper is configured repo-locally right
        // after the clone, so a later push or fetch from any worktree authenticates too.
        assertThat(run(project.workareaPath(), "git", "config", "--get", "credential.helper").strip())
                .isEqualTo(ProjectCheckoutService.CREDENTIAL_HELPER_SCRIPT);
    }

    @Test
    void createProjectWithoutAnAccountConfiguresNoCredentialHelper(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "plain-no-helper", 1L, null);

        // `git config --get` on an unset key exits 1 -- the run() test helper throws
        // on that, so this goes through bash to turn "unset" into a plain empty string.
        assertThat(run(project.workareaPath(), "bash", "-c", "git config --get credential.helper || true").strip())
                .isEmpty();
    }

    @Test
    void createProjectWithAnUnknownAccountFailsBeforeCloningAndNamesIt(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);
        ProjectRepository repository = repositoryOver(tmp);

        List<ILoggingEvent> events = capturingLogs(
                () -> service.createProject(origin.toString(), "unknown-account", 1L, 999L));

        ProjectRecord found = repository.findAll().stream()
                .filter(p -> p.name().equals("unknown-account")).findFirst().orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(repository.findGithubAccountId(found.id())).isEmpty();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("No such GitHub account 999");
        });
        // No clone was attempted: the workarea was never created.
        assertThat(found.workareaPath()).doesNotExist();
    }

    @Test
    void createProjectWithoutAnAccountLeavesNoAccountChosen(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "plain", 1L, null);

        ProjectRepository repository = repositoryOver(tmp);
        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.READY);
        assertThat(repository.findGithubAccountId(project.id())).isEmpty();
    }

    @Test
    void createRepoAndPushWithAnAccountActsAsItThroughGhTokenAndStoresIt(@TempDir Path tmp) throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        Path envLog = tmp.resolve("push-env");
        // A pre-receive hook sees the pushing process's environment, so it can prove
        // the push itself ran with GH_TOKEN set -- a local bare repo can't otherwise
        // tell one pusher from another.
        Path hook = bareRemote.resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, "#!/usr/bin/env bash\nprintf '%s' \"${GH_TOKEN-<unset>}\" > \"" + envLog + "\"\n");
        hook.toFile().setExecutable(true);
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        GhAccount account = seedAccount(tmp, 1L, "work", "work-token", Set.of("repo"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("as-work");
        ProjectRecord project = repository.create("as-work", bareRemote.toString(), workarea, 1L, Instant.now());

        service.createRepoAndPush(project, "my-org", false, account.id());

        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(repository.findGithubAccountId(project.id())).contains(account.id());
        assertThat(Files.readString(ghLog.resolve("calls"))).isEqualTo("repo create my-org/as-work --private\n");
        assertThat(Files.readString(ghLog.resolve("repo-create-env"))).isEqualTo("work-token");
        assertThat(Files.readString(envLog)).isEqualTo("work-token");
        assertThat(run(bareRemote, "git", "log", "-1", "--format=%s", found.defaultBranch()).strip())
                .isEqualTo("Initial commit");
    }

    @Test
    void createRepoAndPushWithAnUnknownAccountCreatesNothing(@TempDir Path tmp) throws Exception {
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("as-nobody");
        ProjectRecord project = repository.create(
                "as-nobody", "https://127.0.0.1:1/my-org/as-nobody.git", workarea, 1L, Instant.now());

        List<ILoggingEvent> events =
                capturingLogs(() -> service.createRepoAndPush(project, "my-org", false, 999L));

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(repository.findGithubAccountId(project.id())).isEmpty();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("No such GitHub account 999");
        });
        // gh was never even invoked -- nothing was created.
        assertThat(ghLog.resolve("calls")).doesNotExist();
        assertThat(workarea).doesNotExist();
    }

    // #531 composes with #550: the workflow-scope gate examines the chosen account's
    // own captured scopes -- never re-queried from GitHub here -- not the host's
    // active login.

    @Test
    void createRepoAndPushRunsTheWorkflowScopeGateOnTheChosenAccountsScopes(@TempDir Path tmp) throws Exception {
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        GhAccount account = seedAccount(tmp, 1L, "work", "work-token", Set.of("repo"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("as-work-no-scope");
        ProjectRecord project = repository.create(
                "as-work-no-scope", "https://127.0.0.1:1/my-org/as-work-no-scope.git", workarea, 1L, Instant.now());

        List<ILoggingEvent> events =
                capturingLogs(() -> service.createRepoAndPush(project, "my-org", true, account.id()));

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        // The chosen account's token was stored, and it is the one the gate judged.
        assertThat(repository.findGithubAccountId(project.id())).contains(account.id());
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("work").contains(ProjectCheckoutService.WORKFLOW_SCOPE);
        });
        // Refused before `gh repo create`: nothing was created on GitHub or on disk.
        assertThat(ghLog.resolve("calls")).doesNotExist();
        assertThat(workarea).doesNotExist();
    }

    @Test
    void createRepoAndPushWithoutAnAccountRunsGhAsTheHostsActiveAccount(@TempDir Path tmp) throws Exception {
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        Path ghLog = Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService service = serviceWithStubGh(tmp, stubGh(tmp, ghLog));
        ProjectRepository repository = repositoryOver(tmp);
        Path workarea = tmp.resolve("workarea").resolve("1").resolve("ambient");
        ProjectRecord project = repository.create("ambient", bareRemote.toString(), workarea, 1L, Instant.now());

        service.createRepoAndPush(project, "my-org", false, null);

        assertThat(repository.findById(project.id()).orElseThrow().status()).isEqualTo(ProjectStatus.READY);
        assertThat(repository.findGithubAccountId(project.id())).isEmpty();
        assertThat(Files.readString(ghLog.resolve("calls"))).isEqualTo("repo create my-org/ambient --private\n");
        // No GH_TOKEN was injected: gh ran as whatever the host has active, exactly as
        // it always has when no account is chosen.
        assertThat(Files.readString(ghLog.resolve("repo-create-env"))).isEqualTo("<unset>");
    }

    @Test
    void tokenCanPushWorkflowsWhenTheAccountsCapturedScopesIncludeWorkflow(@TempDir Path tmp) {
        GhAccount account = seedAccount(tmp, 1L, "work", "work-token", Set.of("repo", "workflow"));
        ProjectCheckoutService service = service(tmp);
        ProjectRecord project = newProjectRecordWithAccount(tmp, "has-workflow", account.id());

        assertThat(service.tokenCanPushWorkflows(project)).isTrue();
    }

    @Test
    void tokenCanPushWorkflowsFailsOpenWithNoAccountChosen(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);
        ProjectRecord project = newProjectRecord(tmp, "no-account");

        assertThat(service.tokenCanPushWorkflows(project)).isTrue();
    }

    @Test
    void setUpLocalRepoAndPushWithoutBootstrapNeverConsultsTheScopeLookup(@TempDir Path tmp) throws Exception {
        // A plain project has no workflow file to push, so an account with only `repo`
        // is all it needs -- the scope gate isn't even involved for a non-bootstrap push.
        Path bareRemote = tmp.resolve("origin.git");
        run(tmp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        ProjectCheckoutService service = service(tmp);
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

    /**
     * A stub {@code gh} for #550's tests (shared with {@code ProjectControllerTest}):
     * only ever asked for {@code repo create} now (the engine no longer shells out
     * for a token lookup) — records every invocation in {@code <log>/calls}, and its
     * {@code GH_TOKEN} in {@code <log>/repo-create-env}. Returns the script's
     * absolute path.
     */
    static String stubGh(Path tmp, Path log) throws IOException {
        Path script = tmp.resolve("stub-gh");
        Files.writeString(script, """
                #!/usr/bin/env bash
                log=%s
                printf '%%s\\n' "$*" >> "$log/calls"
                case "$1 $2" in
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

    /** Like {@link #service(Path)}, but with {@code ghExecutable} standing in for the real gh (#550). */
    private static ProjectCheckoutService serviceWithStubGh(Path tmp, String ghExecutable) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                ghAccounts(tmp), "exit 1", ghExecutable);
    }

    private static ProjectCheckoutService service(Path tmp) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                ghAccounts(tmp));
    }

    /** Like {@link #service(Path)}, but substituting the t-workflow install command (#525) — see STUB_INSTALLER. */
    private static ProjectCheckoutService serviceWithInstallCommand(Path tmp, String installCommand) {
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp),
                ghAccounts(tmp), installCommand);
    }

    private static ProjectRecord newProjectRecord(Path tmp, String name) {
        return repositoryOver(tmp).create(name, "https://github.com/" + NO_SUCH_ORG + "/" + name + ".git",
                tmp.resolve("workarea").resolve("1").resolve(name), 1L, Instant.now());
    }

    private static final String NO_SUCH_ORG = "locklane-550-no-such-org";

    private static ProjectRecord newProjectRecordWithAccount(Path tmp, String name, long githubAccountId) {
        ProjectRecord project = newProjectRecord(tmp, name);
        repositoryOver(tmp).setGithubAccountId(project.id(), githubAccountId);
        return repositoryOver(tmp).findById(project.id()).orElseThrow();
    }

    /** Seeds a real {@code github_accounts} row (#550) — the token is stored encrypted, exactly as production does. */
    private static GhAccount seedAccount(Path tmp, long ownerUserId, String login, String plaintextToken,
            Set<String> scopes) {
        return ghAccounts(tmp).insert(ownerUserId, login, tokenCipher(tmp).encrypt(plaintextToken), scopes,
                Instant.now());
    }

    private static GhAccountRepository ghAccounts(Path tmp) {
        return TestSqliteDatabases.newGhAccountRepository(tmp);
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
