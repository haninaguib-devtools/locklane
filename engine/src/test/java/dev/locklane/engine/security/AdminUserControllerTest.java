package dev.locklane.engine.security;

import dev.locklane.engine.persistence.IssueWorktreeService;
import dev.locklane.engine.persistence.ProjectCheckoutService;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.UserCascadeDeleteService;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #240's admin-only account management done-when at the controller level --
 * route gating itself ({@code SecurityConfig}'s {@code hasRole("ADMIN")}) is a
 * separate concern, exercised by {@link AdminUserRouteIntegrationTest}.
 */
class AdminUserControllerTest {

    @Test
    void createSetsMustChangePasswordAndReturnsTheGeneratedTemporaryPassword(@TempDir Path tmp) {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(tmp);
        AdminUserController controller = controller(tmp, userRepository);

        ResponseEntity<?> response = controller.create(new AdminUserController.CreateUserRequest("newbie", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("temporaryPassword");
        String temporaryPassword = (String) body.get("temporaryPassword");
        assertThat(temporaryPassword).isNotBlank();

        UserRecord created = userRepository.findByUsername("newbie").orElseThrow();
        assertThat(created.mustChangePassword()).isTrue();
        assertThat(created.role()).isEqualTo(UserRecord.Role.USER);
        assertThat(new BCryptPasswordEncoder().matches(temporaryPassword, created.passwordHash())).isTrue();
    }

    @Test
    void createWithAnAdminSuppliedPasswordDoesNotEchoItBack(@TempDir Path tmp) {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(tmp);
        AdminUserController controller = controller(tmp, userRepository);

        ResponseEntity<?> response =
                controller.create(new AdminUserController.CreateUserRequest("newbie", "chosen-by-admin"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).doesNotContainKey("temporaryPassword");

        UserRecord created = userRepository.findByUsername("newbie").orElseThrow();
        assertThat(created.mustChangePassword()).isTrue();
        assertThat(new BCryptPasswordEncoder().matches("chosen-by-admin", created.passwordHash())).isTrue();
    }

    @Test
    void createWithABlankUsernameIsABadRequest(@TempDir Path tmp) {
        AdminUserController controller = controller(tmp, TestSqliteDatabases.newUserRepository(tmp));

        ResponseEntity<?> response = controller.create(new AdminUserController.CreateUserRequest("   ", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createWithATakenUsernameIsAConflict(@TempDir Path tmp) {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(tmp);
        userRepository.create("existing", "hash", Instant.now());
        AdminUserController controller = controller(tmp, userRepository);

        ResponseEntity<?> response = controller.create(new AdminUserController.CreateUserRequest("existing", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listReturnsEveryAccountWithNoPasswordHash(@TempDir Path tmp) {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(tmp);
        userRepository.create("alice", "hash", Instant.now());
        AdminUserController controller = controller(tmp, userRepository);

        assertThat(controller.list())
                .extracting(AdminUserController.UserView::username)
                .contains("alice");
    }

    @Test
    void deleteRemovesTheAccountAndItsOwnedProjectAndSessions(@TempDir Path tmp) throws Exception {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(tmp);
        UserRecord alice = userRepository.create("alice", "hash", Instant.now());
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        Path workarea = tmp.resolve("workarea").resolve(String.valueOf(alice.id())).resolve("proj");
        Files.createDirectories(workarea);
        ProjectRecord project = projectRepository.create("proj", "url", workarea, alice.id(), Instant.now());
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        sessions.recordAttach(project.id() + "-174-rename-toggle", tmp.resolve("wt"), Instant.now(), "alice");
        AdminUserController controller = controller(tmp, userRepository, projectRepository, sessions);

        ResponseEntity<?> response = controller.delete(alice.id(), adminAuthentication("root"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(alice.id())).isEmpty();
        assertThat(projectRepository.findById(project.id())).isEmpty();
        assertThat(workarea).doesNotExist();
    }

    @Test
    void deleteOnAnUnknownIdIsNotFound(@TempDir Path tmp) {
        AdminUserController controller = controller(tmp, TestSqliteDatabases.newUserRepository(tmp));

        ResponseEntity<?> response = controller.delete(999, adminAuthentication("root"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAdminCannotDeleteTheirOwnAccount(@TempDir Path tmp) {
        UserRepository userRepository = TestSqliteDatabases.newUserRepository(tmp);
        UserRecord root = userRepository.create("root", "hash", Instant.now(), UserRecord.Role.ADMIN);
        AdminUserController controller = controller(tmp, userRepository);

        ResponseEntity<?> response = controller.delete(root.id(), adminAuthentication("root"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(userRepository.findById(root.id())).isPresent();
    }

    private static AdminUserController controller(Path tmp, UserRepository userRepository) {
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        WorktreeSessionRepository sessions = TestSqliteDatabases.newRepository(tmp);
        return controller(tmp, userRepository, projectRepository, sessions);
    }

    private static AdminUserController controller(Path tmp, UserRepository userRepository,
            ProjectRepository projectRepository, WorktreeSessionRepository sessions) {
        ProjectCheckoutService checkoutService = new ProjectCheckoutService(projectRepository,
                tmp.resolve("workarea").toString(), Runnable::run,
                new IssueWorktreeService(sessions, TestSqliteDatabases.newNoopAuthorization()), tokenCipher(tmp));
        UserCascadeDeleteService cascadeDeleteService = new UserCascadeDeleteService(projectRepository, checkoutService);
        return new AdminUserController(userRepository, cascadeDeleteService, new BCryptPasswordEncoder());
    }

    private static Authentication adminAuthentication(String username) {
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private static TokenCipher tokenCipher(Path dataDir) {
        try {
            return new TokenCipher(new EncryptionKeyProvider(dataDir.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
