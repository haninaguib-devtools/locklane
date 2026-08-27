package dev.locklane.engine.persistence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Project-level console sessions (#139, part of #138): persistent agent sessions
 * running in the project's own checkout rather than an issue worktree, so a user can
 * start a conversation (and have the agent open an issue via {@code gh}/`/t-open`)
 * before any issue exists. Since #177 a project can have several open at once: POST
 * mints a brand-new session every call, {@code /sessions} lists the open ones, and a
 * specific one is closed by id. Same response shape as {@link WorktreeController}
 * (`sessionId`/`workingDirectory` in place of `worktreeId`/`workingDirectory`) and
 * the same "actual ownership gate is the WebSocket attach, not this endpoint" split.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/console")
public class ProjectConsoleController {

    private final ProjectConsoleService service;

    public ProjectConsoleController(ProjectConsoleService service) {
        this.service = service;
    }

    /**
     * Discovers the project's current console session — the most recently attached
     * open one — if any has actually been attached to before.
     */
    @GetMapping
    public ResponseEntity<Map<String, String>> get(@PathVariable long projectId, Principal principal) {
        return service.find(projectId, principal.getName())
                .map(ProjectConsoleController::toBody)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Mints a brand-new console session id (#177) and reports its working directory. */
    @PostMapping
    public ResponseEntity<Map<String, String>> start(@PathVariable long projectId) {
        return service.start(projectId)
                .map(ProjectConsoleController::toBody)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The project's open console sessions the caller may see, oldest first (#177) —
     * what the consoles page (#179) and tab strip (#178) render.
     */
    @GetMapping("/sessions")
    public List<OpenConsoleView> sessions(@PathVariable long projectId, Principal principal) {
        return service.listOpen(projectId, principal.getName()).stream()
                .map(console -> new OpenConsoleView(console.sessionId(), console.workingDirectory(),
                        console.createdAt().toString(), console.lastAttachedAt().toString()))
                .toList();
    }

    /**
     * Ends the project's current console session — the one {@link #get} reports —
     * for good. 404 when no open console exists or none is the caller's.
     */
    @DeleteMapping
    public ResponseEntity<Void> close(@PathVariable long projectId, Principal principal) {
        if (!service.close(projectId, principal.getName())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Ends one specific console session for good (#177 — the per-tab close). 404 for
     * an id outside this project's console family, one never attached to, or one
     * that isn't the caller's.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> close(@PathVariable long projectId, @PathVariable String sessionId,
            Principal principal) {
        if (!service.close(projectId, sessionId, principal.getName())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    private static Map<String, String> toBody(ProjectConsoleService.ConsoleSession session) {
        return Map.of("sessionId", session.sessionId(), "workingDirectory", session.workingDirectory());
    }

    /** One row of {@link #sessions} — mirrored client-side by #179. */
    public record OpenConsoleView(String sessionId, String workingDirectory, String createdAt, String lastAttachedAt) {
    }
}
