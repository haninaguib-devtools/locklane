package dev.locklane.engine.github;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Serves the sidenav issue list, issue header, and "?" popup data. */
@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final GhIssueCache cache;
    private final IssueDetailService detailService;

    public IssueController(GhIssueCache cache, IssueDetailService detailService) {
        this.cache = cache;
        this.detailService = detailService;
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

    @GetMapping("/{number}/detail")
    public ResponseEntity<IssueDetail> issueDetail(@PathVariable int number) {
        return detailService.detail(number)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
