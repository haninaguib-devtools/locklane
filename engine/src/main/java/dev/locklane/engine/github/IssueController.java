package dev.locklane.engine.github;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Serves the sidenav issue list and issue header data from {@link GhIssueCache}. */
@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final GhIssueCache cache;

    public IssueController(GhIssueCache cache) {
        this.cache = cache;
    }

    @GetMapping
    public List<GhIssue> list() {
        return cache.issues();
    }

    @GetMapping("/{number}")
    public ResponseEntity<GhIssue> detail(@PathVariable int number) {
        return cache.issue(number)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
