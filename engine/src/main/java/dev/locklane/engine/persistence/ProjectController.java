package dev.locklane.engine.persistence;

import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.security.TokenCipher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** CRUD over projects (#42) — creating one kicks off an async clone via {@link ProjectCheckoutService}. */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository repository;
    private final ProjectCheckoutService checkoutService;
    private final TokenCipher tokenCipher;
    private final ProjectGhResources ghResources;

    public ProjectController(ProjectRepository repository, ProjectCheckoutService checkoutService,
            TokenCipher tokenCipher, ProjectGhResources ghResources) {
        this.repository = repository;
        this.checkoutService = checkoutService;
        this.tokenCipher = tokenCipher;
        this.ghResources = ghResources;
    }

    @GetMapping
    public List<ProjectView> list() {
        return repository.findAll().stream().map(ProjectView::from).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateProjectRequest request) {
        if (request.gitUrl() == null || request.gitUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gitUrl is required"));
        }
        ProjectRecord project = checkoutService.createProject(request.gitUrl(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectView.from(project));
    }

    /** Re-clones a failed project from scratch. 404 if it doesn't exist or isn't currently failed. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<ProjectView> retry(@PathVariable long id) {
        return checkoutService.retry(id)
                .map(ProjectView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        if (!checkoutService.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        ghResources.evict(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Stores an encrypted GitHub token for this project (#81), so its issue/PR
     * fetches authenticate as that token against its own repo instead of whatever
     * `gh` identity is already ambiently authenticated. Evicts any cached client for
     * this project so the very next fetch picks up the new token.
     */
    @PutMapping("/{id}/github-token")
    public ResponseEntity<?> setGithubToken(@PathVariable long id, @RequestBody SetGithubTokenRequest request) {
        if (repository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request.token() == null || request.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token is required"));
        }
        repository.setGithubToken(id, tokenCipher.encrypt(request.token()));
        ghResources.evict(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateProjectRequest(String gitUrl, String name) {
    }

    public record SetGithubTokenRequest(String token) {
    }

    /** JSON shape for a project — {@code workareaPath} as a plain string, unlike the persisted {@link ProjectRecord}. */
    public record ProjectView(
            long id, String name, String gitUrl, String workareaPath, String defaultBranch,
            String status, String createdAt) {
        static ProjectView from(ProjectRecord r) {
            return new ProjectView(r.id(), r.name(), r.gitUrl(), r.workareaPath().toString(), r.defaultBranch(),
                    r.status().name(), r.createdAt().toString());
        }
    }
}
