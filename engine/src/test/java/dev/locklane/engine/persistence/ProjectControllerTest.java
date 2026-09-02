package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhAccount;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.template.TemplateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectControllerTest {

    // A syntactically valid GitHub URL (#551's normalizer accepts it) that has no
    // real repository behind it -- the clone fails fast (git fails immediately on an
    // unauthenticated request for a private/nonexistent repo, no real network wait)
    // without ever needing a throwaway local bare repo. Stands in for the pre-#551
    // "/does/not/exist" fixture, which the normalizer now rejects before a project
    // row is even created.
    private static final String UNCLONEABLE_GITHUB_URL =
            "https://github.com/locklane-tests-no-such-org/does-not-exist.git";

    @Test
    void listReturnsOnlyTheCallersOwnProjects(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        repository.create("bar", "url", tmp.resolve("bar"), bob.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        assertThat(controller.list(alice.authentication()))
                .extracting(ProjectController.ProjectView::name).containsExactly("foo");
        assertThat(controller.list(bob.authentication()))
                .extracting(ProjectController.ProjectView::name).containsExactly("bar");
    }

    /**
     * #394 (ADR-105) withdrew the administrator exemption ADR-101 Decision 1 granted:
     * an administrator's project list is their own projects, exactly like anyone
     * else's, and another account's project is simply absent from it.
     */
    @Test
    void listExcludesAnotherUsersProjectFromAnAdmin(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller admin = user(tmp, "root", UserRecord.Role.ADMIN);
        repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        repository.create("roots-own", "url", tmp.resolve("roots-own"), admin.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        assertThat(controller.list(admin.authentication()))
                .extracting(ProjectController.ProjectView::name).containsExactly("roots-own");
    }

    @Test
    void createOwnsTheProjectAsTheAuthenticatedCaller(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest(UNCLONEABLE_GITHUB_URL, "mine", null), alice.authentication());

        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(body.ownerUserId()).isEqualTo(alice.id());
    }

    @Test
    void createWithABlankGitUrlIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest("  ", "name", null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithANullGitUrlIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest(null, "name", null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // #551: gitUrl is normalized before it's ever stored -- accepted shapes collapse
    // to https://github.com/<owner>/<repo>.git, anything else is a 400.

    @Test
    void createWithAnyOtherHostIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest("https://gitlab.com/foo/bar.git", "mine", null),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) response.getBody()).get("error").toString()).contains("GitHub");
    }

    @Test
    void createNormalizesABareOwnerRepoBeforeStoringIt(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest("foo/bar", "mine", null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(repository.findById(body.id()).orElseThrow().gitUrl()).isEqualTo("https://github.com/foo/bar.git");
    }

    @Test
    void createNormalizesAnSshAliasUrlBeforeStoringIt(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest("git@thyme.github.com:foo/bar.git", "mine", null),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(repository.findById(body.id()).orElseThrow().gitUrl()).isEqualTo("https://github.com/foo/bar.git");
    }

    @Test
    void createWithAnUncloneableUrlStillReturnsCreatedWithAFailedProject(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest(UNCLONEABLE_GITHUB_URL, "broken", null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(body.name()).isEqualTo("broken");

        assertThat(controller.list(alice.authentication())).extracting(ProjectController.ProjectView::status)
                .containsExactly(ProjectStatus.FAILED.name());
    }

    // #491's "create new" path. createNew's success case shells out to `gh repo
    // create` for real via ProjectCheckoutService.createNewProject -- never exercised
    // here (a genuine network call regardless of authentication); the record notes it
    // as manually checked instead. Only the validation that returns before reaching
    // the checkout service is covered.

    @Test
    void createNewWithABlankOrgIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("  ", "name", false, null, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNewWithANullOrgIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest(null, "name", false, null, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNewWithABlankNameIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("org", "  ", false, null, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNewWithANullNameIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("org", null, false, null, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // #550: both create endpoints forward the optional githubAccountId to the
    // checkout service, and refuse synchronously -- before any project row exists --
    // one that doesn't resolve to the caller's own account.

    @Test
    void createForwardsTheGithubAccountIdSoItsStoredOnTheProject(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        GhAccount account = seedAccount(tmp, alice.id(), "work", "work-token");
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest(UNCLONEABLE_GITHUB_URL, "mine", account.id()),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(repository.findGithubAccountId(body.id())).contains(account.id());
    }

    @Test
    void createWithAnUnknownGithubAccountIdIsABadRequestBeforeAnyRowExists(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest(UNCLONEABLE_GITHUB_URL, "mine", 999L), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findAllOwnedBy(alice.id())).isEmpty();
    }

    @Test
    void createNewWithAnotherUsersGithubAccountIdIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        GhAccount bobsAccount = seedAccount(tmp, bob.id(), "bobs-account", "bobs-token");
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("my-org", "new-one", false, bobsAccount.id(), null),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findAllOwnedBy(alice.id())).isEmpty();
    }

    // #536: an optional template name on createNew is resolved only through the
    // TemplateStore's listing; an unlisted name is a 400 before any row exists, a
    // listed one is recorded on the project row at creation.

    @Test
    void createNewWithAnUnknownTemplateIsABadRequestBeforeAnyRowExists(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("my-org", "new-one", false, null, "../../etc"),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) response.getBody()).get("error").toString()).contains("../../etc");
        assertThat(repository.findAllOwnedBy(alice.id())).isEmpty();
    }

    @Test
    void createNewWithAListedTemplateRecordsItsNameOnTheProject(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        seedTemplate(tmp);
        ProjectController controller = controllerWithStubGh(tmp, repository);

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("my-org", "new-one", false, null, " custom "),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(body.template()).isEqualTo("custom");
        assertThat(repository.findById(body.id()).orElseThrow().template()).isEqualTo("custom");
        // #537: not yet seeded -- reported as null until the seeded console launches.
        assertThat(body.templateSeededAt()).isNull();
    }

    @Test
    void listReportsWhenATemplatedProjectsSeededConsoleWasLaunched(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now(),
                "springboot-angular");
        repository.markTemplateSeeded(created.id(), Instant.parse("2026-09-01T12:00:00Z"));
        ProjectController controller = controller(tmp, repository);

        assertThat(controller.list(alice.authentication()))
                .extracting(ProjectController.ProjectView::templateSeededAt).containsExactly("2026-09-01T12:00:00Z");
    }

    @Test
    void createNewWithABlankTemplateMeansNone(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controllerWithStubGh(tmp, repository);

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("my-org", "new-one", false, null, "  "),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        assertThat(body.template()).isNull();
    }

    @Test
    void retryOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<ProjectController.ProjectView> response = controller.retry(999, alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void retryOnAnotherUsersProjectIsNotFound(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        repository.markFailed(created.id());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<ProjectController.ProjectView> response = controller.retry(created.id(), bob.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The by-id half of #394 (ADR-105): every operation that funnels through
     * {@code findAuthorized} refuses an administrator on another account's project,
     * as a 404 indistinguishable from the project not existing — and leaves the
     * project itself untouched.
     */
    @Test
    void anAdminIsDeniedEveryOperationOnAnotherUsersProject(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller admin = user(tmp, "root", UserRecord.Role.ADMIN);
        ProjectRecord created = repository.create("foo", "/does/not/exist", tmp.resolve("foo"), alice.id(), Instant.now());
        repository.markFailed(created.id());
        ProjectController controller = controller(tmp, repository);

        assertThat(controller.retry(created.id(), admin.authentication()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.delete(created.id(), admin.authentication()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.setGithubAccount(created.id(),
                new ProjectController.SetGithubAccountRequest(1L), admin.authentication()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.setAccentColor(created.id(),
                new ProjectController.SetAccentColorRequest("#c15f3c"), admin.authentication()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(repository.findGithubAccountId(created.id())).isEmpty();
        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::accentColor).isNull();
        assertThat(controller.list(alice.authentication()))
                .extracting(ProjectController.ProjectView::name).containsExactly("foo");
    }

    @Test
    void deleteRemovesAKnownProjectAndIsNoContent(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.delete(created.id(), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.list(alice.authentication())).isEmpty();
    }

    @Test
    void deleteOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        assertThat(controller.delete(999, alice.authentication()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteOnAnotherUsersProjectIsNotFoundAndLeavesItIntact(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.delete(created.id(), bob.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.list(alice.authentication()))
                .extracting(ProjectController.ProjectView::name).containsExactly("foo");
    }

    @Test
    void deleteOnAProjectWithAnOpenSessionIsAConflictWithAMessage(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        sessions.recordAttach(created.id() + "-174-rename-toggle", tmp.resolve("wt"), Instant.now(), "alice");
        ProjectController controller =
                controller(tmp, repository, new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()));

        ResponseEntity<?> response = controller.delete(created.id(), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertThat(body).containsKey("error");
        assertThat(controller.list(alice.authentication()))
                .extracting(ProjectController.ProjectView::name).containsExactly("foo");
    }

    // #550: replaces the old #81 "paste a raw token" endpoint with choosing one of
    // the caller's own GitHub accounts.

    @Test
    void settingAGithubAccountStoresIt(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        GhAccount account = seedAccount(tmp, alice.id(), "work", "ghp_secret");
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setGithubAccount(
                created.id(), new ProjectController.SetGithubAccountRequest(account.id()), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repository.findGithubAccountId(created.id())).contains(account.id());
    }

    @Test
    void settingAGithubAccountOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        GhAccount account = seedAccount(tmp, alice.id(), "work", "ghp_secret");
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.setGithubAccount(
                999, new ProjectController.SetGithubAccountRequest(account.id()), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void settingAGithubAccountOnAnotherUsersProjectIsNotFound(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        GhAccount account = seedAccount(tmp, bob.id(), "work", "ghp_secret");
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setGithubAccount(
                created.id(), new ProjectController.SetGithubAccountRequest(account.id()), bob.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repository.findGithubAccountId(created.id())).isEmpty();
    }

    @Test
    void settingAnotherUsersGithubAccountIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        GhAccount bobsAccount = seedAccount(tmp, bob.id(), "bobs-account", "bobs-token");
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setGithubAccount(created.id(),
                new ProjectController.SetGithubAccountRequest(bobsAccount.id()), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findGithubAccountId(created.id())).isEmpty();
    }

    @Test
    void settingANullGithubAccountIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setGithubAccount(
                created.id(), new ProjectController.SetGithubAccountRequest(null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void settingAnAccentColorStoresIt(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setAccentColor(
                created.id(), new ProjectController.SetAccentColorRequest("#c15f3c"), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::accentColor).isEqualTo("#c15f3c");
    }

    @Test
    void settingAnAccentColorOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.setAccentColor(
                999, new ProjectController.SetAccentColorRequest("#c15f3c"), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void settingAnInvalidAccentColorIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setAccentColor(
                created.id(), new ProjectController.SetAccentColorRequest("not-a-color"), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void settingANullAccentColorIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setAccentColor(
                created.id(), new ProjectController.SetAccentColorRequest(null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static ProjectController controller(Path tmp, ProjectRepository repository) throws IOException {
        return controller(tmp, repository,
                new IssueWorktreeService(TestSqliteDatabases.newRepository(tmp), TestSqliteDatabases.newNoopAuthorization()));
    }

    private static ProjectController controller(Path tmp, ProjectRepository repository,
            IssueWorktreeService issueWorktreeService) throws IOException {
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        GhAccountRepository ghAccountRepository = TestSqliteDatabases.newGhAccountRepository(tmp);
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(repository,
                tmp.resolve("workarea").toString(), Runnable::run, issueWorktreeService, tokenCipher,
                ghAccountRepository);
        ProjectGhResources ghResources = new ProjectGhResources(repository, ghAccountRepository, tokenCipher,
                (path, token) -> {
                    throw new UnsupportedOperationException("not exercised by ProjectController's own tests");
                });
        return new ProjectController(repository, checkoutService, ghResources,
                TestSqliteDatabases.newUserRepository(tmp), templateStore(tmp), ghAccountRepository);
    }

    /** Like {@link #controller(Path, ProjectRepository)}, with the #550 stub gh in place of the real CLI. */
    private static ProjectController controllerWithStubGh(Path tmp, ProjectRepository repository) throws IOException {
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        GhAccountRepository ghAccountRepository = TestSqliteDatabases.newGhAccountRepository(tmp);
        Path ghLog = java.nio.file.Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(repository,
                tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(TestSqliteDatabases.newRepository(tmp), TestSqliteDatabases.newNoopAuthorization()),
                tokenCipher, ghAccountRepository, "exit 1", ProjectCheckoutServiceTest.stubGh(tmp, ghLog));
        ProjectGhResources ghResources = new ProjectGhResources(repository, ghAccountRepository, tokenCipher,
                (path, token) -> {
                    throw new UnsupportedOperationException("not exercised by ProjectController's own tests");
                });
        return new ProjectController(repository, checkoutService, ghResources,
                TestSqliteDatabases.newUserRepository(tmp), templateStore(tmp), ghAccountRepository);
    }

    /** Seeds a real {@code github_accounts} row (#550), owned by {@code ownerUserId}. */
    private static GhAccount seedAccount(Path tmp, long ownerUserId, String login, String plaintextToken)
            throws IOException {
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        return TestSqliteDatabases.newGhAccountRepository(tmp)
                .insert(ownerUserId, login, tokenCipher.encrypt(plaintextToken), Set.of("repo"), Instant.now());
    }

    /**
     * A template store (#536) over {@code <tmp>/templates}, holding one host template
     * named {@code custom} when that directory has been seeded by {@link #seedTemplate}.
     */
    private static TemplateStore templateStore(Path tmp) {
        // The production constructor takes the data dir and looks under <data-dir>/templates.
        return new TemplateStore(tmp.toString());
    }

    private static void seedTemplate(Path tmp) throws IOException {
        Path dir = java.nio.file.Files.createDirectories(tmp.resolve("templates").resolve("custom"));
        java.nio.file.Files.writeString(dir.resolve("template.md"),
                "---\ntitle: Custom\ndescription: a host template\n---\n# Custom\n");
    }

    /** A real {@code users} row (so {@link ProjectController} can resolve it) plus a matching {@link Authentication}. */
    private static Caller user(Path tmp, String username, UserRecord.Role role) {
        UserRecord created = TestSqliteDatabases.newUserRepository(tmp).create(username, "bcrypt-hash", Instant.now(), role);
        Authentication authentication = new UsernamePasswordAuthenticationToken(username, null, List.of());
        return new Caller(created.id(), authentication);
    }

    private record Caller(long id, Authentication authentication) {
    }
}
