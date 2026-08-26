package dev.locklane.engine.github;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the sidenav issue list/tree, issue header, and "?" popup data. Nested under
 * a project id since #43; since #81, the data itself genuinely comes from that
 * project's own repo (its own token, if stored, against its own checkout) rather
 * than one shared repo for every project — 404 for an unknown project id, same as
 * an unknown issue.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/issues")
public class IssueController {

    private final ProjectGhResources resources;

    public IssueController(ProjectGhResources resources) {
        this.resources = resources;
    }

    @GetMapping
    public ResponseEntity<List<GhIssue>> list(@PathVariable long projectId) {
        return resources.forProject(projectId)
                .map(ctx -> ResponseEntity.ok(ctx.cache().issues()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Registered before "/{number}" in source order; Spring matches the literal
    // "/tree" segment ahead of the "{number}" path variable regardless, but keeping
    // them adjacent here documents that the two must never collide.
    @GetMapping("/tree")
    public ResponseEntity<List<TreeNode>> tree(@PathVariable long projectId) {
        return resources.forProject(projectId)
                .map(ctx -> ResponseEntity.ok(ctx.treeService().tree()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{number}")
    public ResponseEntity<GhIssue> detail(@PathVariable long projectId, @PathVariable int number) {
        return resources.forProject(projectId)
                .flatMap(ctx -> ctx.cache().issue(number))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{number}/detail")
    public ResponseEntity<IssueDetail> issueDetail(@PathVariable long projectId, @PathVariable int number) {
        return resources.forProject(projectId)
                .flatMap(ctx -> ctx.detailService().detail(number))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
