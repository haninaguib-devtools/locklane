package dev.locklane.engine.persistence;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Serves every open console session across all issues that the caller may
 * see (#32's header indicator/picker) — same ownership visibility as
 * {@link WorktreeController#worktrees}, just not scoped to one issue.
 */
@RestController
public class ConsolesController {

    private final IssueWorktreeService service;

    public ConsolesController(IssueWorktreeService service) {
        this.service = service;
    }

    @GetMapping("/api/consoles")
    public List<String> consoles(Principal principal) {
        return service.allWorktreeIds(principal.getName());
    }
}
