package dev.locklane.engine;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves {@code index.html} for the client's {@code /issues/:id} route (#31)
 * so a direct load or reload reaches the SPA instead of a 404 -- the root
 * path already works via Boot's welcome-page handling of {@code index.html}.
 * Extend this mapping if a future top-level Angular route is added.
 */
@Controller
public class SpaFallbackController {

    @GetMapping("/issues/{id}")
    public String forwardIssueRoute() {
        return "forward:/index.html";
    }
}
