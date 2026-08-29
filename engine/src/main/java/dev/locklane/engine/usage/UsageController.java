package dev.locklane.engine.usage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the sidebar's usage widget (#137). One snapshot for every provider — the
 * widget shows them side by side, and per-provider "unavailable" is carried inside
 * {@link ProviderUsage} rather than as an HTTP error, so this endpoint has nothing to
 * 404 or 500 on: it always returns 200 with whatever {@link UsageService} has.
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public UsageSnapshot usage() {
        return usageService.snapshot();
    }
}
