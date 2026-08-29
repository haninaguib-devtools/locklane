package dev.locklane.engine.persistence;

import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.security.SecurityConfig;
import dev.locklane.engine.security.TokenCipher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD over projects (#42) — creating one kicks off an async clone via
 * {@link ProjectCheckoutService}.
 *
 * <p>Every project belongs to exactly one account (#239, ADR-007 Decision 1):
 * {@code list} returns only the caller's own projects (every project for an admin
 * caller), and every by-id operation below is scoped through
 * {@link #findAuthorized(long, Authentication)}, which resolves to empty — reported
 * as 404, indistinguishable from the project simply not existing, so a non-owner
 * can't tell someone else's project id apart from an unused one — for a project that
 * exists but isn't the caller's and the caller isn't admin. {@link SecurityConfig}
 * gates every path here as {@code authenticated()}, so {@code authentication} is
 * never null by the time a request arrives.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository repository;
    private final ProjectCheckoutService checkoutService;
    private final TokenCipher tokenCipher;
    private final ProjectGhResources ghResources;
    private final UserRepository userRepository;

    public ProjectController(ProjectRepository repository, ProjectCheckoutService checkoutService,
            TokenCipher tokenCipher, ProjectGhResources ghResources, UserRepository userRepository) {
        this.repository = repository;
        this.checkoutService = checkoutService;
        this.tokenCipher = tokenCipher;
        this.ghResources = ghResources;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<ProjectView> list(Authentication authentication) {
        UserRecord caller = currentUser(authentication);
        List<ProjectRecord> projects = caller.role() == UserRecord.Role.ADMIN
                ? repository.findAll()
                : repository.findAllOwnedBy(caller.id());
        return projects.stream().map(ProjectView::from).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateProjectRequest request, Authentication authentication) {
        if (request.gitUrl() == null || request.gitUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gitUrl is required"));
        }
        UserRecord caller = currentUser(authentication);
        ProjectRecord project = checkoutService.createProject(request.gitUrl(), request.name(), caller.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectView.from(project));
    }

    /** Re-clones a failed project from scratch. 404 if it doesn't exist, isn't the caller's, or isn't currently failed. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ProjectView> retry(@PathVariable long id, Authentication authentication) {
        if (findAuthorized(id, authentication).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return checkoutService.retry(id)
                .map(ProjectView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 409 (#231) when the project still has an open worktree or console session. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, Authentication authentication) {
        if (findAuthorized(id, authentication).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return switch (checkoutService.delete(id)) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case HAS_OPEN_SESSIONS -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "This project has an open worktree or console — close it before deleting the project."));
            case DELETED -> {
                ghResources.evict(id);
                yield ResponseEntity.noContent().build();
            }
        };
    }

    /**
     * Stores an encrypted GitHub token for this project (#81), so its issue/PR
     * fetches authenticate as that token against its own repo instead of whatever
     * `gh` identity is already ambiently authenticated. Evicts any cached client for
     * this project so the very next fetch picks up the new token.
     */
    @PutMapping("/{id}/github-token")
    public ResponseEntity<?> setGithubToken(
            @PathVariable long id, @RequestBody SetGithubTokenRequest request, Authentication authentication) {
        if (findAuthorized(id, authentication).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token is required"));
        }
        repository.setGithubToken(id, tokenCipher.encrypt(request.token()));
        ghResources.evict(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The project, if it exists and the caller is allowed to see it — its owner, or
     * an admin (#239). Empty either when the project doesn't exist or when it
     * belongs to someone else, deliberately indistinguishable to the caller.
     */
    private Optional<ProjectRecord> findAuthorized(long id, Authentication authentication) {
        Optional<ProjectRecord> project = repository.findById(id);
        if (project.isEmpty()) {
            return Optional.empty();
        }
        UserRecord caller = currentUser(authentication);
        if (caller.role() == UserRecord.Role.ADMIN || project.get().ownerUserId() == caller.id()) {
            return project;
        }
        return Optional.empty();
    }

    private UserRecord currentUser(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated as '" + authentication.getName() + "' but no such user row exists"));
    }

    public record CreateProjectRequest(String gitUrl, String name) {
    }

    public record SetGithubTokenRequest(String token) {
    }

    /** JSON shape for a project — {@code workareaPath} as a plain string, unlike the persisted {@link ProjectRecord}. */
    public record ProjectView(
            long id, long ownerUserId, String name, String gitUrl, String workareaPath, String defaultBranch,
            String status, String createdAt) {
        static ProjectView from(ProjectRecord r) {
            return new ProjectView(r.id(), r.ownerUserId(), r.name(), r.gitUrl(), r.workareaPath().toString(),
                    r.defaultBranch(), r.status().name(), r.createdAt().toString());
        }
    }
}
