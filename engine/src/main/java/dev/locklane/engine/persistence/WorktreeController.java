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
 * Serves the worktree-tabs row for a project's issue, starts a new session on
 * demand, and closes one for good. All three endpoints require authentication
 * ({@code SecurityConfig}) and see only sessions the caller owns, or that have no
 * recorded owner (#48). Nested under a project id since #43 — worktrees live inside
 * that project's own checkout.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/issues")
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
    public List<String> worktrees(@PathVariable long projectId, @PathVariable int number, Principal principal) {
        return service.worktreeIdsForIssue(projectId, number, principal.getName());
    }

    /**
     * Starts a new session for the project's issue. {@code worktree=false} opens it
     * directly against the project's main checkout instead — no
     * {@code git worktree add} required (#29). 404 for an unknown or not-yet-ready
     * project, same as an unknown issue.
     */
    @PostMapping("/{number}/worktrees")
    public ResponseEntity<Map<String, String>> startSession(@PathVariable long projectId, @PathVariable int number,
            @RequestParam(defaultValue = "true") boolean worktree, Principal principal) {
        return creationService.startSession(projectId, number, worktree, principal.getName())
                .map(started -> ResponseEntity.ok(
                        Map.of("worktreeId", started.worktreeId(), "workingDirectory", started.workingDirectory())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The past Claude/Codex conversations captured in this issue's consoles (#102),
     * newest first — what the Overview tab's session list shows (#103). Same
     * visibility rule as {@link #worktrees}, applied to the console each
     * conversation was captured in.
     */
    @GetMapping("/{number}/resume-sessions")
    public List<ResumeSessionView> resumeSessions(@PathVariable long projectId, @PathVariable int number,
            Principal principal) {
        return service.resumeSessionsForIssue(projectId, number, principal.getName()).stream()
                .map(record -> new ResumeSessionView(record.worktreeId(), record.tool(), record.resumeId(),
                        record.capturedAt().toString()))
                .toList();
    }

    /**
     * Mints a brand-new console session for resuming a past conversation (#103), in
     * the working directory of the console ({@code from}) the conversation was
     * captured in. The client attaches to the returned session id with
     * {@code cmd=<tool>&resume=<id>} exactly as it attaches any other new console.
     * {@code 404} when {@code from} carries no conversation the caller may see —
     * same visibility rule as {@link #resumeSessions}.
     */
    @PostMapping("/{number}/resume-sessions/reopen")
    public ResponseEntity<Map<String, String>> reopenSession(@PathVariable long projectId, @PathVariable int number,
            @RequestParam String from, Principal principal) {
        boolean visible = service.resumeSessionsForIssue(projectId, number, principal.getName()).stream()
                .anyMatch(record -> record.worktreeId().equals(from));
        if (!visible) {
            return ResponseEntity.notFound().build();
        }
        return creationService.reopenSession(projectId, number, from)
                .map(started -> ResponseEntity.ok(
                        Map.of("worktreeId", started.worktreeId(), "workingDirectory", started.workingDirectory())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** One row of {@link #resumeSessions} — mirrored client-side as {@code ResumeSession}. */
    public record ResumeSessionView(String worktreeId, String tool, String resumeId, String capturedAt) {
    }

    /**
     * Ends a session for good (#75) — kills its process and forgets its record, unlike
     * a client merely disconnecting (#7). {@code 404} for a session the caller cannot
     * see, same visibility rule as {@link #worktrees}.
     */
    @DeleteMapping("/{number}/worktrees/{worktreeId}")
    public ResponseEntity<Void> closeSession(@PathVariable long projectId, @PathVariable int number,
            @PathVariable String worktreeId, Principal principal) {
        if (!service.worktreeIdsForIssue(projectId, number, principal.getName()).contains(worktreeId)) {
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
