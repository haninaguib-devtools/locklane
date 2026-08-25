package dev.locklane.engine.persistence;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Serves the worktree-tabs row for an issue. */
@RestController
@RequestMapping("/api/issues")
public class WorktreeController {

    private final IssueWorktreeService service;

    public WorktreeController(IssueWorktreeService service) {
        this.service = service;
    }

    @GetMapping("/{number}/worktrees")
    public List<String> worktrees(@PathVariable int number) {
        return service.worktreeIdsForIssue(number);
    }
}
