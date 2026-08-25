package dev.locklane.engine.persistence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Serves the worktree-tabs row for an issue, and starts a new session on demand. */
@RestController
@RequestMapping("/api/issues")
public class WorktreeController {

    private final IssueWorktreeService service;
    private final WorktreeCreationService creationService;

    public WorktreeController(IssueWorktreeService service, WorktreeCreationService creationService) {
        this.service = service;
        this.creationService = creationService;
    }

    @GetMapping("/{number}/worktrees")
    public List<String> worktrees(@PathVariable int number) {
        return service.worktreeIdsForIssue(number);
    }

    @PostMapping("/{number}/worktrees")
    public ResponseEntity<Map<String, String>> startSession(@PathVariable int number) {
        return creationService.startSession(number)
                .map(started -> ResponseEntity.ok(
                        Map.of("worktreeId", started.worktreeId(), "workingDirectory", started.workingDirectory())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(WorktreeCreationService.WorktreeCreationException.class)
    public ResponseEntity<Map<String, String>> onCreationFailure(WorktreeCreationService.WorktreeCreationException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }
}
