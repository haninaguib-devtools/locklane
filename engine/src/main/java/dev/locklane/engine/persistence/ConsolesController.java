package dev.locklane.engine.persistence;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Serves every open console session across all of one project's issues that the
 * caller may see (#32's header indicator/picker) — same ownership visibility as
 * {@link WorktreeController#worktrees}, just not scoped to one issue. Nested under
 * a project id since #43.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/consoles")
public class ConsolesController {

    private final IssueWorktreeService service;

    public ConsolesController(IssueWorktreeService service) {
        this.service = service;
    }

    @GetMapping
    public List<String> consoles(@PathVariable long projectId, Principal principal) {
        return service.allWorktreeIds(projectId, principal.getName());
    }
}
