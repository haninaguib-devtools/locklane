package dev.locklane.engine.github;

import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain calls into {@link GhAccountsController}, the same style
 * {@code ProjectControllerTest} uses — no MockMvc, so no need for the security
 * filter chain (that gate is {@link GhAccountsRouteIntegrationTest}'s job) or for a
 * populated {@code SecurityContext}; the caller's identity is passed directly as an
 * {@link Authentication}, resolved to a real row so {@code currentUser} can find it.
 */
class GhAccountsControllerTest {

    private static final GhAccount ACCOUNT = new GhAccount(7, 1, "haninaguib", Set.of("repo", "workflow"),
            Instant.parse("2026-08-01T00:00:00Z"));

    @Test
    void listsTheCallersAccountsAsJson(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        when(service.accountsFor(1L)).thenReturn(List.of(ACCOUNT));
        Caller caller = user(tmp, "alice");
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        GhAccountsController.AccountsResponse response = controller.list(caller.authentication());

        assertThat(response.accounts()).hasSize(1);
        assertThat(response.accounts().get(0).login()).isEqualTo("haninaguib");
        assertThat(response.accounts().get(0).hasWorkflowScope()).isTrue();
        assertThat(response.accounts().get(0).scopes()).containsExactlyInAnyOrder("repo", "workflow");
    }

    @Test
    void theAccountViewCarriesNeedsReconnectAndNoTokenField(@TempDir Path tmp) throws Exception {
        // #656: a renewal that failed for good is what the accounts page shows as
        // "needs reconnection"; the tokens themselves never leave the engine.
        GhAccount failed = new GhAccount(8, 1, "dead", Set.of("repo"), Instant.parse("2026-09-03T10:00:00Z"),
                Instant.parse("2026-09-03T11:00:00Z"), Instant.parse("2027-03-03T10:00:00Z"),
                Instant.parse("2026-09-03T11:01:00Z"));
        GhAccountsService service = mock(GhAccountsService.class);
        when(service.accountsFor(1L)).thenReturn(List.of(ACCOUNT, failed));
        Caller caller = user(tmp, "alice");
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        GhAccountsController.AccountsResponse response = controller.list(caller.authentication());

        assertThat(response.accounts().get(0).needsReconnect()).isFalse();
        assertThat(response.accounts().get(0).tokenExpiresAt()).isNull();
        assertThat(response.accounts().get(1).needsReconnect()).isTrue();
        assertThat(response.accounts().get(1).tokenExpiresAt()).isEqualTo("2026-09-03T11:00:00Z");
        assertThat(GhAccountsController.AccountView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(name -> name.toLowerCase().contains("token") && !name.equals("tokenExpiresAt"));
    }

    @Test
    void addByTokenReturns201WithTheAccount(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.addByToken(caller.id(), "ghp_pasted")).thenReturn(new GhAccountsService.AddResult.Added(ACCOUNT));
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.addByToken(
                new GhAccountsController.AddTokenRequest("ghp_pasted"), caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((GhAccountsController.AccountView) response.getBody()).login()).isEqualTo("haninaguib");
    }

    @Test
    void addByTokenReturns400WhenInvalid(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.addByToken(caller.id(), "bad")).thenReturn(
                new GhAccountsService.AddResult.Invalid("could not verify this token with GitHub"));
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.addByToken(
                new GhAccountsController.AddTokenRequest("bad"), caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("could not verify this token with GitHub");
    }

    @Test
    void startDeviceFlowReturns201WithTheCode(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.startDeviceFlow(caller.id())).thenReturn(new GhAccountsService.DeviceFlowStartResult.Started(
                "flow-1", "ABCD-1234", "https://github.com/login/device", 900));
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.startDeviceFlow(caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        GhAccountsController.DeviceFlowStartedView body = (GhAccountsController.DeviceFlowStartedView) response.getBody();
        assertThat(body.flowId()).isEqualTo("flow-1");
        assertThat(body.userCode()).isEqualTo("ABCD-1234");
    }

    @Test
    void startDeviceFlowReturns501WhenNotConfigured(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.startDeviceFlow(caller.id())).thenReturn(new GhAccountsService.DeviceFlowStartResult.NotConfigured());
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.startDeviceFlow(caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }

    @Test
    void startDeviceFlowReturns502WhenGitHubCannotBeReached(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.startDeviceFlow(caller.id())).thenReturn(
                new GhAccountsService.DeviceFlowStartResult.Failed("could not reach GitHub to start sign-in"));
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.startDeviceFlow(caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void deviceFlowStatusReturnsTheCurrentState(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.statusOf(caller.id(), "flow-1")).thenReturn(Optional.of(new GhAccountsService.DeviceFlowStatus(
                GhAccountsService.DeviceFlowStatus.Status.COMPLETE, ACCOUNT, null)));
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.deviceFlowStatus("flow-1", caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        GhAccountsController.DeviceFlowStatusView body = (GhAccountsController.DeviceFlowStatusView) response.getBody();
        assertThat(body.status()).isEqualTo("COMPLETE");
        assertThat(body.account().login()).isEqualTo("haninaguib");
    }

    @Test
    void deviceFlowStatusOnAnUnknownFlowIsNotFound(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.statusOf(caller.id(), "unknown")).thenReturn(Optional.empty());
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.deviceFlowStatus("unknown", caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeIsNoContentOnSuccess(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.remove(caller.id(), 7L)).thenReturn(new GhAccountsService.RemoveResult.Removed());
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.remove(7L, caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void removeIsNotFoundForAnUnknownAccount(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.remove(caller.id(), 999L)).thenReturn(new GhAccountsService.RemoveResult.NotFound());
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.remove(999L, caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeIsConflictWhenStillInUse(@TempDir Path tmp) throws Exception {
        GhAccountsService service = mock(GhAccountsService.class);
        Caller caller = user(tmp, "alice");
        when(service.remove(caller.id(), 7L)).thenReturn(new GhAccountsService.RemoveResult.InUse(List.of("my-project")));
        GhAccountsController controller = new GhAccountsController(service, userRepository(tmp));

        ResponseEntity<?> response = controller.remove(7L, caller.authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) response.getBody()).get("error").toString()).contains("my-project");
    }

    private static UserRepository userRepository(Path tmp) {
        return TestSqliteDatabases.newUserRepository(tmp);
    }

    private static Caller user(Path tmp, String username) {
        UserRecord created =
                TestSqliteDatabases.newUserRepository(tmp).create(username, "bcrypt-hash", Instant.now(), UserRecord.Role.USER);
        Authentication authentication = new UsernamePasswordAuthenticationToken(username, null, List.of());
        return new Caller(created.id(), authentication);
    }

    private record Caller(long id, Authentication authentication) {
    }
}
