package dev.locklane.engine.github;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the sidenav issue list/tree, issue header, and "?" popup data. Nested under
 * a project id since #43, though the data itself still comes from one shared repo
 * for every project — a separate, deferred concern (see #43's task record).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/issues")
public class IssueController {

    private final GhIssueCache cache;
    private final IssueDetailService detailService;
    private final IssueTreeService treeService;

    public IssueController(GhIssueCache cache, IssueDetailService detailService, IssueTreeService treeService) {
        this.cache = cache;
        this.detailService = detailService;
        this.treeService = treeService;
    }

    @GetMapping
    public List<GhIssue> list() {
        return cache.issues();
    }

    // Registered before "/{number}" in source order; Spring matches the literal
    // "/tree" segment ahead of the "{number}" path variable regardless, but keeping
    // them adjacent here documents that the two must never collide.
    @GetMapping("/tree")
    public List<TreeNode> tree() {
        return treeService.tree();
    }

    @GetMapping("/{number}")
    public ResponseEntity<GhIssue> detail(@PathVariable int number) {
        return cache.issue(number)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{number}/detail")
    public ResponseEntity<IssueDetail> issueDetail(@PathVariable int number) {
        return detailService.detail(number)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
