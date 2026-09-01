package dev.locklane.engine.persistence;

import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
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

import static org.assertj.core.api.Assertions.assertThat;

class ProjectControllerTest {

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
                new ProjectController.CreateProjectRequest("/does/not/exist", "mine", null), alice.authentication());

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

    @Test
    void createWithAnUncloneableUrlStillReturnsCreatedWithAFailedProject(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest("/does/not/exist", "broken", null), alice.authentication());

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
                new ProjectController.CreateNewProjectRequest("  ", "name", false, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNewWithANullOrgIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest(null, "name", false, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNewWithABlankNameIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("org", "  ", false, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createNewWithANullNameIsABadRequest(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("org", null, false, null), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // #532: both create endpoints forward the optional githubLogin to the checkout
    // service, which resolves it through gh -- a stub here (see
    // ProjectCheckoutServiceTest.stubGh), so no real gh and no network.

    @Test
    void createForwardsTheGithubLoginSoItsTokenIsStoredOnTheProject(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controllerWithStubGh(tmp, repository);

        ResponseEntity<?> response = controller.create(
                new ProjectController.CreateProjectRequest("/does/not/exist", "mine", "work"), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        assertThat(cipher.decrypt(repository.findGithubToken(body.id()).orElseThrow())).isEqualTo("work-token");
    }

    @Test
    void createNewForwardsTheGithubLoginAndAnUnknownOneFailsTheProject(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controllerWithStubGh(tmp, repository);

        ResponseEntity<?> response = controller.createNew(
                new ProjectController.CreateNewProjectRequest("my-org", "new-one", false, "nobody"),
                alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProjectController.ProjectView body = (ProjectController.ProjectView) response.getBody();
        // Failed at the token lookup, before any `gh repo create` -- the stub never saw one.
        assertThat(repository.findById(body.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(repository.findGithubToken(body.id())).isEmpty();
        assertThat(java.nio.file.Files.readString(tmp.resolve("gh-log").resolve("calls")))
                .isEqualTo("auth token --user nobody\n");
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
        assertThat(controller.setGithubToken(created.id(),
                new ProjectController.SetGithubTokenRequest("ghp_secret"), admin.authentication()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.setAccentColor(created.id(),
                new ProjectController.SetAccentColorRequest("#c15f3c"), admin.authentication()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(repository.findGithubToken(created.id())).isEmpty();
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

    @Test
    void settingAGithubTokenStoresItEncrypted(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setGithubToken(
                created.id(), new ProjectController.SetGithubTokenRequest("ghp_secret"), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String stored = repository.findGithubToken(created.id()).orElseThrow();
        assertThat(stored).isNotEqualTo("ghp_secret"); // encrypted, not plaintext
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        assertThat(cipher.decrypt(stored)).isEqualTo("ghp_secret");
    }

    @Test
    void settingAGithubTokenOnAnUnknownProjectIsNotFound(@TempDir Path tmp) throws IOException {
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectController controller = controller(tmp, TestSqliteDatabases.newProjectRepository(tmp));

        ResponseEntity<?> response = controller.setGithubToken(
                999, new ProjectController.SetGithubTokenRequest("ghp_secret"), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void settingAGithubTokenOnAnotherUsersProjectIsNotFound(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setGithubToken(
                created.id(), new ProjectController.SetGithubTokenRequest("ghp_secret"), bob.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repository.findGithubToken(created.id())).isEmpty();
    }

    @Test
    void settingABlankGithubTokenIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setGithubToken(
                created.id(), new ProjectController.SetGithubTokenRequest("  "), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findGithubToken(created.id())).isEmpty();
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
    void settingAnAccentColorOnAnotherUsersProjectIsNotFound(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        Caller bob = user(tmp, "bob", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setAccentColor(
                created.id(), new ProjectController.SetAccentColorRequest("#c15f3c"), bob.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::accentColor).isNull();
    }

    @Test
    void settingAnInvalidAccentColorIsABadRequest(@TempDir Path tmp) throws IOException {
        ProjectRepository repository = TestSqliteDatabases.newProjectRepository(tmp);
        Caller alice = user(tmp, "alice", UserRecord.Role.USER);
        ProjectRecord created = repository.create("foo", "url", tmp.resolve("foo"), alice.id(), Instant.now());
        ProjectController controller = controller(tmp, repository);

        ResponseEntity<?> response = controller.setAccentColor(
                created.id(), new ProjectController.SetAccentColorRequest("terracotta"), alice.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(repository.findById(created.id())).isPresent().get()
                .extracting(ProjectRecord::accentColor).isNull();
    }

    @Test
    void settingAnUnsetAccentColorIsABadRequest(@TempDir Path tmp) throws IOException {
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
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(repository,
                tmp.resolve("workarea").toString(), Runnable::run, issueWorktreeService, tokenCipher);
        ProjectGhResources ghResources = new ProjectGhResources(repository, tokenCipher, (path, token) -> {
            throw new UnsupportedOperationException("not exercised by ProjectController's own tests");
        });
        return new ProjectController(repository, checkoutService, tokenCipher, ghResources,
                TestSqliteDatabases.newUserRepository(tmp));
    }

    /** Like {@link #controller(Path, ProjectRepository)}, with the #532 stub gh in place of the real CLI. */
    private static ProjectController controllerWithStubGh(Path tmp, ProjectRepository repository) throws IOException {
        TokenCipher tokenCipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        Path ghLog = java.nio.file.Files.createDirectories(tmp.resolve("gh-log"));
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(repository,
                tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(TestSqliteDatabases.newRepository(tmp), TestSqliteDatabases.newNoopAuthorization()),
                tokenCipher, java.util.Optional::empty, "exit 1", token -> java.util.Optional.empty(),
                ProjectCheckoutServiceTest.stubGh(tmp, ghLog));
        ProjectGhResources ghResources = new ProjectGhResources(repository, tokenCipher, (path, token) -> {
            throw new UnsupportedOperationException("not exercised by ProjectController's own tests");
        });
        return new ProjectController(repository, checkoutService, tokenCipher, ghResources,
                TestSqliteDatabases.newUserRepository(tmp));
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
