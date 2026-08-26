package dev.locklane.engine.persistence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.locklane.engine.pty.SessionRegistry;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Serves the worktree-tabs row for an issue, starts a new session on demand, and
 * closes one for good. All three endpoints require authentication
 * ({@code SecurityConfig}) and see only sessions the caller owns, or that have no
 * recorded owner (#48).
 */
@RestController
@RequestMapping("/api/issues")
public class WorktreeController {

    private final IssueWorktreeService service;
    private final WorktreeCreationService creationService;
    private final SessionRegistry sessionRegistry;

    public WorktreeController(IssueWorktreeService service, WorktreeCreationService creationService,
            SessionRegistry sessionRegistry) {
        this.service = service;
        this.creationService = creationService;
        this.sessionRegistry = sessionRegistry;
    }

    @GetMapping("/{number}/worktrees")
    public List<String> worktrees(@PathVariable int number, Principal principal) {
        return service.worktreeIdsForIssue(number, principal.getName());
    }

    /**
     * Starts a new session for the issue. {@code worktree=false} opens it directly
     * against the main checkout instead — no {@code git worktree add} required (#29).
     */
    @PostMapping("/{number}/worktrees")
    public ResponseEntity<Map<String, String>> startSession(@PathVariable int number,
            @RequestParam(defaultValue = "true") boolean worktree, Principal principal) {
        return creationService.startSession(number, worktree, principal.getName())
                .map(started -> ResponseEntity.ok(
                        Map.of("worktreeId", started.worktreeId(), "workingDirectory", started.workingDirectory())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Ends a session for good (#75) — kills its process and forgets its record, unlike
     * a client merely disconnecting (#7). {@code 404} for a session the caller cannot
     * see, same visibility rule as {@link #worktrees}.
     */
    @DeleteMapping("/{number}/worktrees/{worktreeId}")
    public ResponseEntity<Void> closeSession(@PathVariable int number, @PathVariable String worktreeId,
            Principal principal) {
        if (!service.worktreeIdsForIssue(number, principal.getName()).contains(worktreeId)) {
            return ResponseEntity.notFound().build();
        }
        sessionRegistry.close(worktreeId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(WorktreeCreationService.WorktreeCreationException.class)
    public ResponseEntity<Map<String, String>> onCreationFailure(WorktreeCreationService.WorktreeCreationException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }
}
