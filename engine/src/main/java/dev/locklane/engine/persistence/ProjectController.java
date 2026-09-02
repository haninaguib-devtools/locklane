package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhAccount;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.security.SecurityConfig;
import dev.locklane.engine.template.ProjectTemplate;
import dev.locklane.engine.template.TemplateStore;
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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final ProjectGhResources ghResources;
    private final UserRepository userRepository;
    private final TemplateStore templateStore;
    private final GhAccountRepository ghAccountRepository;

    public ProjectController(ProjectRepository repository, ProjectCheckoutService checkoutService,
            ProjectGhResources ghResources, UserRepository userRepository,
            TemplateStore templateStore, GhAccountRepository ghAccountRepository) {
        this.repository = repository;
        this.checkoutService = checkoutService;
        this.ghResources = ghResources;
        this.userRepository = userRepository;
        this.templateStore = templateStore;
        this.ghAccountRepository = ghAccountRepository;
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
        Optional<String> normalizedUrl = GitRemoteUrl.normalize(request.gitUrl());
        if (normalizedUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gitUrl must be a GitHub repository: "
                    + "https://github.com/<owner>/<repo>, git@<host>:<owner>/<repo>, or <owner>/<repo>"));
        }
        UserRecord caller = currentUser(authentication);
        Optional<ResponseEntity<?>> accountError = ownedAccountError(request.githubAccountId(), caller.id());
        if (accountError.isPresent()) {
            return accountError.get();
        }
        ProjectRecord project = checkoutService.createProject(
                normalizedUrl.get(), request.name(), caller.id(), request.githubAccountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectView.from(project));
    }

    /**
     * The "Create new" side of the Add Project dialog (#491): creates a brand-new
     * GitHub repository at {@code org/name} via {@code gh} instead of importing one
     * that already exists, then registers it through the same async, status-tracked
     * flow as {@link #create}. Both accept an optional {@code githubAccountId} (#550),
     * one of the caller's own GitHub accounts, for the project to act as; absent, no
     * account is chosen and the project has no GitHub credentials of its own. This one
     * also accepts an optional {@code template} (#536): the name of a project template
     * on this host, resolved only through {@link TemplateStore#find} — never joined
     * onto a path — and rejected with 400 before any row or repository exists when it
     * is not listed.
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
        Optional<ResponseEntity<?>> accountError = ownedAccountError(request.githubAccountId(), caller.id());
        if (accountError.isPresent()) {
            return accountError.get();
        }
        ProjectTemplate template = null;
        if (request.template() != null && !request.template().isBlank()) {
            Optional<ProjectTemplate> found = templateStore.find(request.template().strip());
            if (found.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "template '" + request.template().strip() + "' is not one of the templates on this host"));
            }
            template = found.get();
        }
        ProjectRecord project = checkoutService.createNewProject(
                request.org(), request.name(), request.bootstrapTWorkflow(), caller.id(), request.githubAccountId(),
                template);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectView.from(project));
    }

    /**
     * A 400 body when {@code githubAccountId} is non-null and does not resolve to one
     * of {@code callerId}'s own GitHub accounts (#550) — checked synchronously, before
     * any project row exists, the same way an unlisted template is (#536). Empty (no
     * error) for {@code null} — no account chosen is always valid.
     */
    private Optional<ResponseEntity<?>> ownedAccountError(Long githubAccountId, long callerId) {
        if (githubAccountId == null) {
            return Optional.empty();
        }
        Optional<GhAccount> account = ghAccountRepository.findById(githubAccountId);
        if (account.isEmpty() || account.get().ownerUserId() != callerId) {
            return Optional.of(ResponseEntity.badRequest().body(Map.of("error", "no such GitHub account")));
        }
        return Optional.empty();
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
     * Sets which of the caller's own GitHub accounts (#550) this project acts as, so
     * its issue/PR fetches and git/gh operations authenticate as that account instead
     * of whatever identity is otherwise ambiently available. Replaces
     * {@code PUT /api/projects/{id}/github-token} (#81). Evicts any cached client for
     * this project so the very next fetch picks up the change.
     */
    @PutMapping("/{id}/github-account")
    public ResponseEntity<?> setGithubAccount(
            @PathVariable long id, @RequestBody SetGithubAccountRequest request, Authentication authentication) {
        UserRecord caller = currentUser(authentication);
        if (findAuthorized(id, authentication).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<ResponseEntity<?>> accountError = ownedAccountError(request.githubAccountId(), caller.id());
        if (accountError.isPresent()) {
            return accountError.get();
        }
        if (request.githubAccountId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "githubAccountId is required"));
        }
        repository.setGithubAccountId(id, request.githubAccountId());
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
     * Persists the caller's new sidenav order (#541) — {@code orderedIds} must name
     * exactly the caller's own current projects, in the order they now belong in;
     * {@code sort_order} then becomes each id's index in that list. A 400 when the set
     * of ids doesn't match (missing one, a duplicate, or an id belonging to someone
     * else) rather than silently reordering a subset and leaving the rest stranded at
     * their old positions.
     */
    @PutMapping("/order")
    public ResponseEntity<?> setOrder(@RequestBody SetOrderRequest request, Authentication authentication) {
        UserRecord caller = currentUser(authentication);
        List<Long> orderedIds = request.orderedIds();
        if (orderedIds == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderedIds is required"));
        }
        List<Long> ownedIds = repository.findAllOwnedBy(caller.id()).stream().map(ProjectRecord::id).toList();
        Set<Long> orderedIdSet = new HashSet<>(orderedIds);
        if (orderedIdSet.size() != orderedIds.size() || !orderedIdSet.equals(new HashSet<>(ownedIds))) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "orderedIds must name exactly the caller's own projects, with no duplicates"));
        }
        repository.setOrder(orderedIds);
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

    /** {@code githubAccountId} (#550) is optional — {@code null} when the client chose no account. */
    public record CreateProjectRequest(String gitUrl, String name, Long githubAccountId) {
    }

    /**
     * {@code githubAccountId} (#550) is optional — {@code null} when the client chose
     * no account; so is {@code template} (#536) — {@code null} when the client chose
     * none.
     */
    public record CreateNewProjectRequest(String org, String name, boolean bootstrapTWorkflow, Long githubAccountId,
            String template) {
    }

    public record SetGithubAccountRequest(Long githubAccountId) {
    }

    public record SetAccentColorRequest(String accentColor) {
    }

    /** {@code orderedIds} (#541) is the caller's full project id list, in the order they now belong in. */
    public record SetOrderRequest(List<Long> orderedIds) {
    }

    /** JSON shape for a project — {@code workareaPath} as a plain string, unlike the persisted {@link ProjectRecord}. */
    public record ProjectView(
            long id, long ownerUserId, String name, String gitUrl, String workareaPath, String defaultBranch,
            String status, String createdAt, String accentColor, String template, String templateSeededAt,
            int sortOrder) {
        static ProjectView from(ProjectRecord r) {
            return new ProjectView(r.id(), r.ownerUserId(), r.name(), r.gitUrl(), r.workareaPath().toString(),
                    r.defaultBranch(), r.status().name(), r.createdAt().toString(), r.accentColor(), r.template(),
                    r.templateSeededAt() == null ? null : r.templateSeededAt().toString(), r.sortOrder());
        }
    }
}
