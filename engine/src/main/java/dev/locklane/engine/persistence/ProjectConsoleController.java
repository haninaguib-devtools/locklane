package dev.locklane.engine.persistence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

/**
 * The project-level console session (#139, part of #138): one persistent agent
 * session per project, running in the project's own checkout rather than an issue
 * worktree, so a user can start a conversation (and have the agent open an issue via
 * {@code gh}/`/t-open`) before any issue exists. Same response shape as
 * {@link WorktreeController} (`sessionId`/`workingDirectory` in place of
 * `worktreeId`/`workingDirectory`) and the same "actual ownership gate is the
 * WebSocket attach, not this endpoint" split.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/console")
public class ProjectConsoleController {

    private final ProjectConsoleService service;

    public ProjectConsoleController(ProjectConsoleService service) {
        this.service = service;
    }

    /** Discovers the project's console session, if one has actually been attached to before. */
    @GetMapping
    public ResponseEntity<Map<String, String>> get(@PathVariable long projectId, Principal principal) {
        return service.find(projectId, principal.getName())
                .map(ProjectConsoleController::toBody)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Mints (or reports) the project's console session id and working directory. */
    @PostMapping
    public ResponseEntity<Map<String, String>> start(@PathVariable long projectId) {
        return service.start(projectId)
                .map(ProjectConsoleController::toBody)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Ends the project's console session for good. 404 for one that doesn't exist or isn't the caller's. */
    @DeleteMapping
    public ResponseEntity<Void> close(@PathVariable long projectId, Principal principal) {
        if (!service.close(projectId, principal.getName())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    private static Map<String, String> toBody(ProjectConsoleService.ConsoleSession session) {
        return Map.of("sessionId", session.sessionId(), "workingDirectory", session.workingDirectory());
    }
}
