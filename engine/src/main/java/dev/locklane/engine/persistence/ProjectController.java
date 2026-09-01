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
import java.util.regex.Pattern;

/**
 * CRUD over projects (#42) — creating one kicks off an async clone via
 * {@link ProjectCheckoutService}.
 *
 * <p>Every project belongs to exactly one account (#239, ADR-101 Decision 1) and is
 * private to it (#394, ADR-105, which withdrew the administrator exemption ADR-101
 * Decisions 1 and 6 had granted): {@code list} returns only the caller's own
 * projects, whatever their role, and every by-id operation below is scoped through
 * {@link #findAuthorized(long, Authentication)}, which resolves to empty — reported
 * as 404, indistinguishable from the project simply not existing, so a non-owner
 * can't tell someone else's project id apart from an unused one — for a project that
 * exists but isn't the caller's. No role, administrator included, is exempt.
 * {@link SecurityConfig} gates every path here as {@code authenticated()}, so
 * {@code authentication} is never null by the time a request arrives.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    /** #427's accepted shape for a project's accent color — a 6-digit hex triplet. */
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

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
        return repository.findAllOwnedBy(caller.id()).stream().map(ProjectView::from).toList();
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

    /**
     * The "Create new" side of the Add Project dialog (#491): creates a brand-new
     * GitHub repository at {@code org/name} via {@code gh} instead of importing one
     * that already exists, then registers it through the same async, status-tracked
     * flow as {@link #create}.
     */
    @PostMapping("/new")
    public ResponseEntity<?> createNew(@RequestBody CreateNewProjectRequest request, Authentication authentication) {
        if (request.org() == null || request.org().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "org is required"));
        }
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        UserRecord caller = currentUser(authentication);
        ProjectRecord project = checkoutService.createNewProject(
                request.org(), request.name(), request.bootstrapTWorkflow(), caller.id());
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
     * Sets this project's accent color (#427), so its own pages can be tinted with a
     * lighter version of it — separate from, and with no effect on, the global,
     * client-only accent that keeps driving the navbar/header. Ownership-gated the
     * same as every other by-id operation here; the value must be a 6-digit hex color.
     */
    @PutMapping("/{id}/accent-color")
    public ResponseEntity<?> setAccentColor(
            @PathVariable long id, @RequestBody SetAccentColorRequest request, Authentication authentication) {
        if (findAuthorized(id, authentication).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request.accentColor() == null || !HEX_COLOR.matcher(request.accentColor()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "accentColor must be a hex color like #c15f3c"));
        }
        repository.setAccentColor(id, request.accentColor());
        return ResponseEntity.noContent().build();
    }

    /**
     * The project, if it exists and the caller owns it (#239, #394). Ownership is the
     * whole of the check — no role is exempt (ADR-105). Empty either when the project
     * doesn't exist or when it belongs to someone else, deliberately
     * indistinguishable to the caller.
     */
    private Optional<ProjectRecord> findAuthorized(long id, Authentication authentication) {
        Optional<ProjectRecord> project = repository.findById(id);
        if (project.isEmpty()) {
            return Optional.empty();
        }
        UserRecord caller = currentUser(authentication);
        return project.filter(p -> p.ownerUserId() == caller.id());
    }

    private UserRecord currentUser(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "authenticated as '" + authentication.getName() + "' but no such user row exists"));
    }

    public record CreateProjectRequest(String gitUrl, String name) {
    }

    public record CreateNewProjectRequest(String org, String name, boolean bootstrapTWorkflow) {
    }

    public record SetGithubTokenRequest(String token) {
    }

    public record SetAccentColorRequest(String accentColor) {
    }

    /** JSON shape for a project — {@code workareaPath} as a plain string, unlike the persisted {@link ProjectRecord}. */
    public record ProjectView(
            long id, long ownerUserId, String name, String gitUrl, String workareaPath, String defaultBranch,
            String status, String createdAt, String accentColor) {
        static ProjectView from(ProjectRecord r) {
            return new ProjectView(r.id(), r.ownerUserId(), r.name(), r.gitUrl(), r.workareaPath().toString(),
                    r.defaultBranch(), r.status().name(), r.createdAt().toString(), r.accentColor());
        }
    }
}
