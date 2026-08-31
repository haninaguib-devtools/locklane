package dev.locklane.engine.persistence;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Serves every open console session across all of one project's issues, plus its
 * own project-level consoles (#194), that the caller may see (#32's header
 * indicator/picker) — same ownership visibility as {@link WorktreeController#worktrees},
 * just not scoped to one issue. Nested under a project id since #43.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/consoles")
public class ConsolesController {

    private final IssueWorktreeService service;
    private final FileManagerLauncher fileManagerLauncher;

    public ConsolesController(IssueWorktreeService service, FileManagerLauncher fileManagerLauncher) {
        this.service = service;
        this.fileManagerLauncher = fileManagerLauncher;
    }

    @GetMapping
    public List<String> consoles(@PathVariable long projectId, Principal principal) {
        return service.allWorktreeIds(projectId, principal.getName());
    }

    /**
     * Reveals {@code id}'s worktree in the local OS's file manager (#441) — same
     * visibility rule as {@link #consoles}, so this can't be used to reveal a console
     * outside the caller's own project. 404 for a console id the caller may not see,
     * or one with no known working directory (never closed but never actually
     * attached to, or already closed).
     */
    @PostMapping("/{id}/reveal-in-file-manager")
    public ResponseEntity<Void> reveal(@PathVariable long projectId, @PathVariable String id, Principal principal) {
        if (!service.allWorktreeIds(projectId, principal.getName()).contains(id)) {
            return ResponseEntity.notFound().build();
        }
        return fileManagerLauncher.reveal(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
