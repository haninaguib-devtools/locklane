package dev.locklane.engine.github;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The GitHub accounts page (#550): list the accounts the caller has signed in to
 * Locklane, add one by pasting a token or by GitHub's OAuth device flow, remove
 * one. Gated as authenticated in {@code SecurityConfig} — account-scoped, not
 * project-scoped, like {@code /api/agents/installed}; every method below is further
 * scoped to the caller's own accounts (ADR-105), the same shape
 * {@code ProjectController} uses.
 */
@RestController
@RequestMapping("/api/github/accounts")
public class GhAccountsController {

    private final GhAccountsService service;
    private final UserRepository userRepository;

    public GhAccountsController(GhAccountsService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public AccountsResponse list(Authentication authentication) {
        List<AccountView> accounts = service.accountsFor(currentUser(authentication).id()).stream()
                .map(AccountView::from).toList();
        return new AccountsResponse(accounts);
    }

    @PostMapping("/token")
    public ResponseEntity<?> addByToken(@RequestBody AddTokenRequest request, Authentication authentication) {
        GhAccountsService.AddResult result = service.addByToken(currentUser(authentication).id(), request.token());
        return switch (result) {
            case GhAccountsService.AddResult.Added added ->
                    ResponseEntity.status(HttpStatus.CREATED).body(AccountView.from(added.account()));
            case GhAccountsService.AddResult.Invalid invalid ->
                    ResponseEntity.badRequest().body(Map.of("error", invalid.message()));
        };
    }

    @PostMapping("/device/start")
    public ResponseEntity<?> startDeviceFlow(Authentication authentication) {
        GhAccountsService.DeviceFlowStartResult result = service.startDeviceFlow(currentUser(authentication).id());
        return switch (result) {
            case GhAccountsService.DeviceFlowStartResult.Started started -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(new DeviceFlowStartedView(started.flowId(), started.userCode(), started.verificationUri(),
                            started.expiresInSeconds()));
            case GhAccountsService.DeviceFlowStartResult.NotConfigured ignored -> ResponseEntity
                    .status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "no GitHub OAuth App is configured on this host "
                            + "(locklane.github.oauth-client-id) — paste a token instead, or ask the operator to "
                            + "set one up"));
            case GhAccountsService.DeviceFlowStartResult.Failed failed ->
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", failed.message()));
        };
    }

    @GetMapping("/device/{flowId}")
    public ResponseEntity<?> deviceFlowStatus(@PathVariable String flowId, Authentication authentication) {
        return service.statusOf(currentUser(authentication).id(), flowId)
                .map(status -> ResponseEntity.ok(DeviceFlowStatusView.from(status)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable long id, Authentication authentication) {
        GhAccountsService.RemoveResult result = service.remove(currentUser(authentication).id(), id);
        return switch (result) {
            case GhAccountsService.RemoveResult.Removed ignored -> ResponseEntity.noContent().build();
            case GhAccountsService.RemoveResult.NotFound ignored -> ResponseEntity.notFound().build();
            case GhAccountsService.RemoveResult.InUse inUse -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("error", "still used by " + String.join(", ", inUse.projectNames())
                            + " — pick a different account for " + (inUse.projectNames().size() == 1 ? "it" : "them")
                            + " first"));
        };
    }

    private UserRecord currentUser(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated as '" + authentication.getName() + "' but no such user row exists"));
    }

    record AccountsResponse(List<AccountView> accounts) {
    }

    record AddTokenRequest(String token) {
    }

    /**
     * The API shape of a {@link GhAccount} — never its token, never its refresh
     * token. {@code needsReconnect} (#656) is true once the engine can no longer keep
     * the account working on its own; {@code tokenExpiresAt} is null for a token that
     * does not expire.
     */
    record AccountView(long id, String login, List<String> scopes, boolean hasWorkflowScope, String createdAt,
            boolean needsReconnect, String tokenExpiresAt) {
        static AccountView from(GhAccount account) {
            return new AccountView(account.id(), account.login(), List.copyOf(account.scopes()),
                    account.hasWorkflowScope(), account.createdAt().toString(), account.needsReconnect(Instant.now()),
                    account.tokenExpiresAt() == null ? null : account.tokenExpiresAt().toString());
        }
    }

    record DeviceFlowStartedView(String flowId, String userCode, String verificationUri, int expiresInSeconds) {
    }

    record DeviceFlowStatusView(String status, AccountView account, String errorMessage) {
        static DeviceFlowStatusView from(GhAccountsService.DeviceFlowStatus status) {
            return new DeviceFlowStatusView(status.status().name(),
                    status.account() == null ? null : AccountView.from(status.account()), status.errorMessage());
        }
    }
}
