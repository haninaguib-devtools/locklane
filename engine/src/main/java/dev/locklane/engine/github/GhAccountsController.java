package dev.locklane.engine.github;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the Add Project dialog's "GitHub account" picker (#532) the accounts
 * {@code gh} is logged into on the engine host, with which one is active. Gated as
 * authenticated in {@code SecurityConfig} — account-scoped like
 * {@code /api/agents/installed}, not project-scoped: it describes the host, not a
 * project.
 */
@RestController
@RequestMapping("/api/github")
public class GhAccountsController {

    private final GhAccountsService service;

    public GhAccountsController(GhAccountsService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    public GhAccountsResponse accounts() {
        return new GhAccountsResponse(service.accounts());
    }

    record GhAccountsResponse(List<GhAccount> accounts) {
    }
}
