package dev.locklane.engine.persistence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The project page's worktree list (#320) — every worktree tied to one project's
 * issues, its manual "remove worktree" action, and the page-level "run cleanup now"
 * trigger. Unlike {@link WorktreeController} (per-issue, ownership-filtered — one
 * user's own consoles for one issue), this is a project-wide, system-level view with
 * no ownership filter, matching {@link IssueWorktreeService#allIssueWorktrees()} and
 * the periodic sweep it feeds (#319): a worktree left behind by any user is still
 * something a human overseeing the project needs to see and can remove or sweep,
 * whether or not they are the one who created it.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/worktrees")
public class ProjectWorktreesController {

    private final ProjectWorktreesService service;

    public ProjectWorktreesController(ProjectWorktreesService service) {
        this.service = service;
    }

    /** Every worktree tied to this project's issues, each with its dirty/clean and session-attached status. */
    @GetMapping
    public List<ProjectWorktreesService.WorktreeRow> list(@PathVariable long projectId) {
        return service.listForProject(projectId);
    }

    /**
     * Removes one worktree, applying the same guard as the periodic sweep (#319):
     * issue closed, clean, unattached. {@code 404} for a worktree id this project does
     * not have; {@code 409} with the guard's own reason when it refuses.
     */
    @DeleteMapping("/{worktreeId}")
    public ResponseEntity<Map<String, String>> remove(@PathVariable long projectId, @PathVariable String worktreeId) {
        ProjectWorktreesService.RemovalResult result = service.remove(projectId, worktreeId);
        if (!result.found()) {
            return ResponseEntity.notFound().build();
        }
        if (!result.removed()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", result.refusalReason()));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs the same sweep the schedule runs (#319's {@code WorktreeCleanupSweeper}),
     * right now — see {@link ProjectWorktreesService} for why this is system-wide
     * rather than scoped to {@code projectId}. Returns the worktree ids actually
     * removed so the client can report the outcome before it re-fetches the list.
     */
    @PostMapping("/cleanup")
    public Map<String, List<String>> cleanup(@PathVariable long projectId) {
        return Map.of("removed", service.runCleanupNow());
    }
}
