package dev.locklane.engine.persistence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Shell-kind console sessions over REST (#445, part of #444): minting a shell at a
 * project's issue worktree or main checkout, and the cross-project listing the
 * singleton Shells window's sidenav renders. Same response shape as
 * {@link ProjectConsoleController} ({@code sessionId}/{@code workingDirectory}) and
 * the same "the actual ownership gate is the WebSocket attach, not the creation
 * endpoint" split; the listing applies the usual project-owner visibility rule.
 */
@RestController
public class ShellsController {

    private final ShellSessionService service;

    public ShellsController(ShellSessionService service) {
        this.service = service;
    }

    /**
     * Mints a brand-new shell session at the given worktree directory — an issue's
     * worktree when {@code issueNumber} is given, the project's main checkout when
     * it is absent — and reports the id to attach a WebSocket to with
     * {@code cmd=shell}. 400 for a missing/blank directory; 404 for an unknown or
     * not-yet-ready project.
     */
    @PostMapping("/api/projects/{projectId}/shells")
    public ResponseEntity<Map<String, String>> open(@PathVariable long projectId,
            @RequestBody OpenShellRequest request, Principal principal) {
        String username = principal.getName();
        if (request == null || request.workingDirectory() == null || request.workingDirectory().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return service.open(projectId, request.issueNumber(), Path.of(request.workingDirectory()), username)
                .map(session -> Map.of("sessionId", session.sessionId(),
                        "workingDirectory", session.workingDirectory()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Every open shell session the caller may see, across every project, oldest
     * first — one call carries everything the Shells sidenav needs to group by
     * project and, within one, by issue or the main checkout.
     */
    @GetMapping("/api/shells")
    public List<OpenShellView> shells(Principal principal) {
        return service.listOpen(principal.getName()).stream()
                .map(shell -> new OpenShellView(shell.sessionId(), shell.projectId(), shell.issueNumber(),
                        shell.mainCheckout(), shell.workingDirectory(), shell.createdAt().toString(),
                        shell.lastAttachedAt().toString(), shell.displayName()))
                .toList();
    }

    /** The body of {@link #open} — {@code issueNumber} absent means the project's main checkout. */
    public record OpenShellRequest(Integer issueNumber, String workingDirectory) {
    }

    /**
     * One row of {@link #shells} — mirrored client-side by the Shells window (#446).
     * {@code issueNumber} is {@code null} — and {@code mainCheckout} true — for a
     * shell at the project's main checkout.
     */
    public record OpenShellView(String sessionId, long projectId, Integer issueNumber, boolean mainCheckout,
            String workingDirectory, String createdAt, String lastAttachedAt, String displayName) {
    }
}
