package dev.locklane.engine.persistence;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Project-level console sessions (#139, part of #138): persistent agent sessions with
 * no issue of their own, so a user can start a conversation (and have the agent open
 * an issue via {@code gh}/`/t-open`) before any issue exists. Since #314 each session
 * gets its own fresh git worktree rather than sharing the project's checkout. Since
 * #177 a project can have several open at once: POST mints a brand-new session every
 * call, {@code /sessions} lists the open ones, and a specific one is closed by id.
 * Same response shape as {@link WorktreeController}
 * (`sessionId`/`workingDirectory` in place of `worktreeId`/`workingDirectory`) and
 * the same "actual ownership gate is the WebSocket attach, not this endpoint" split.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/console")
public class ProjectConsoleController {

    private static final Logger log = LoggerFactory.getLogger(ProjectConsoleController.class);

    private final ProjectConsoleService service;
    private final ConsoleSessionTitles titles;

    public ProjectConsoleController(ProjectConsoleService service, ConsoleSessionTitles titles) {
        this.service = service;
        this.titles = titles;
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

    /**
     * Mints a brand-new console session id (#177) backed by its own fresh git
     * worktree (#314) and reports that worktree's directory.
     */
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
                        console.createdAt().toString(), console.lastAttachedAt().toString(),
                        console.displayName()))
                .toList();
    }

    /**
     * The past Claude/Codex/OpenCode conversations captured in this project's own
     * consoles (#372), newest first — the project-page counterpart of
     * {@link WorktreeController#resumeSessions}, and deliberately the same
     * {@link WorktreeController.ResumeSessionView} row shape, so the client renders
     * both lists with one component and one model. Same visibility rule as
     * {@link #sessions}, applied to the console each conversation was captured in.
     */
    @GetMapping("/resume-sessions")
    public List<WorktreeController.ResumeSessionView> resumeSessions(@PathVariable long projectId,
            Principal principal) {
        List<ConsoleResumeSessionRecord> records = service.resumeSessionsForProject(projectId, principal.getName());
        Map<String, String> byConversation = titles.titlesFor(records.stream()
                .map(record -> new ConsoleSessionTitles.Sighting(record.tool(), record.resumeId(),
                        service.conversationDirectory(projectId, record.worktreeId()).orElse(null)))
                .toList());
        return records.stream()
                .map(record -> new WorktreeController.ResumeSessionView(record.worktreeId(), record.tool(),
                        record.resumeId(), record.capturedAt().toString(),
                        byConversation.get(record.tool() + ":" + record.resumeId())))
                .toList();
    }

    /**
     * Mints a brand-new console session for resuming a past conversation (#372), in
     * the working directory of the console ({@code from}) it was captured in. The
     * client attaches to the returned session id with {@code cmd=<tool>&resume=<id>}
     * exactly as it attaches any other new console. {@code 404} when {@code from}
     * carries no conversation the caller may see — same visibility rule as
     * {@link #resumeSessions}.
     */
    @PostMapping("/resume-sessions/reopen")
    public ResponseEntity<Map<String, String>> reopenSession(@PathVariable long projectId,
            @RequestParam String from, Principal principal) {
        boolean visible = service.resumeSessionsForProject(projectId, principal.getName()).stream()
                .anyMatch(record -> record.worktreeId().equals(from));
        if (!visible) {
            return ResponseEntity.notFound().build();
        }
        return service.reopenSession(projectId, from)
                .map(ProjectConsoleController::toBody)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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

    /**
     * Names one of this project's console tabs, or clears the name (#393): a
     * {@code null}/blank {@code name} restores the client's own auto-generated label.
     * 404 for an id outside this project's console family, one never attached to, or
     * one that isn't the caller's — the same gate the per-tab close applies, so a
     * user cannot rename another owner's console. 400 for a name longer than
     * {@link ProjectConsoleService#MAX_DISPLAY_NAME_LENGTH} characters after
     * trimming; the name is stored as text and never interpreted, so the client
     * renders it as text too.
     */
    @PutMapping("/{sessionId}/name")
    public ResponseEntity<Void> rename(@PathVariable long projectId, @PathVariable String sessionId,
            @RequestBody RenameRequest request, Principal principal) {
        return switch (service.rename(projectId, sessionId, principal.getName(),
                request == null ? null : request.name())) {
            case RENAMED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case TOO_LONG -> ResponseEntity.badRequest().build();
        };
    }

    /** Same failure mode {@link WorktreeController} handles: {@code git worktree add} itself failed (#314). */
    @ExceptionHandler(WorktreeCreationService.WorktreeCreationException.class)
    public ResponseEntity<Map<String, String>> onCreationFailure(WorktreeCreationService.WorktreeCreationException e,
            HttpServletRequest request) {
        log.error("Console worktree creation failed on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }

    private static Map<String, String> toBody(ProjectConsoleService.ConsoleSession session) {
        return Map.of("sessionId", session.sessionId(), "workingDirectory", session.workingDirectory());
    }

    /**
     * One row of {@link #sessions} — mirrored client-side by #179.
     * {@code displayName} is the name the user gave this tab (#393), {@code null}
     * when they gave it none.
     */
    public record OpenConsoleView(String sessionId, String workingDirectory, String createdAt, String lastAttachedAt,
            String displayName) {
    }

    /** The body of {@link #rename} — {@code null} or blank clears the name (#393). */
    public record RenameRequest(String name) {
    }
}
