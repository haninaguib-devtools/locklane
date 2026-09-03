package dev.locklane.engine.github;

import dev.locklane.engine.ws.EventBroadcaster;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    private final EventBroadcaster eventBroadcaster;

    public IssueController(ProjectGhResources resources, EventBroadcaster eventBroadcaster) {
        this.resources = resources;
        this.eventBroadcaster = eventBroadcaster;
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
    /**
     * {@code fresh=true} (#140) forces a live {@code gh} fetch before serving the
     * tree, bypassing whatever {@link GhIssueCache} is still holding from the
     * scheduled 30s refresh — for a caller that just left a console session where
     * an agent may have created an issue via {@code gh}, and wants it to show up
     * immediately rather than waiting on the next scheduled poll. When that forced
     * fetch turns up a change, it broadcasts {@code issuesChanged} the same way the
     * scheduled {@code ProjectGhResources.refreshAll} does (#545), so other open
     * tabs learn about it too rather than only the caller that triggered it.
     *
     * <p>The response carries the outcome of the project's most recent GitHub fetch
     * alongside the tree (#619) -- a forced refresh that fails still answers 200 with
     * the cached tree, and this is what lets the caller tell that apart from an
     * up-to-date one. A forced refresh whose outcome moved (started failing, stopped
     * failing, or failing differently) broadcasts {@code githubRefreshStatus} the same
     * way the scheduled poll does.
     */
    @GetMapping("/tree")
    public ResponseEntity<TreeResponse> tree(@PathVariable long projectId,
            @RequestParam(defaultValue = "false") boolean fresh) {
        return resources.forProject(projectId)
                .map(ctx -> {
                    if (fresh) {
                        GhRefreshStatus before = ctx.cache().status();
                        boolean changed = ctx.cache().refresh();
                        ProjectGhResources.broadcastStatusIfMoved(eventBroadcaster, projectId, before, ctx.cache().status());
                        if (changed) {
                            eventBroadcaster.broadcast("issuesChanged", Map.of("projectId", projectId));
                        }
                    }
                    return ResponseEntity.ok(new TreeResponse(ctx.treeService().tree(), ctx.cache().status()));
                })
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
