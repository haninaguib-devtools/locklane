package dev.locklane.engine.codeserver;

import dev.locklane.engine.persistence.IssueWorktreeService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

/**
 * Decides, for one proxied IDE request (#655), whether the caller may reach the
 * console it names and where its code-server is — the same decision for the HTTP
 * proxy and the WebSocket proxy, made in one place. The visibility rule is exactly the
 * one {@code ConsolesController#openIde} applies when starting the process
 * ({@link IssueWorktreeService#allWorktreeIds}: the console must belong to the named
 * project and that project to the caller, CONSTITUTION.md §4.5), so the proxy can
 * never admit a request the start endpoint would have refused. A console whose IDE is
 * not running resolves to nothing: the proxy never starts one, and it never
 * distinguishes "not yours" from "not there" to a caller.
 */
@Component
public class CodeServerProxyAuthorization {

    private final IssueWorktreeService worktrees;
    private final CodeServerService codeServerService;

    public CodeServerProxyAuthorization(IssueWorktreeService worktrees, CodeServerService codeServerService) {
        this.worktrees = worktrees;
        this.codeServerService = codeServerService;
    }

    /**
     * The loopback base of the code-server behind {@code path}, when {@code username}
     * may see that console and its IDE is running; empty otherwise. A {@code null}
     * username (no authenticated caller — {@code SecurityConfig} never lets one this
     * far, but the check fails closed regardless) is always empty.
     */
    public Optional<URI> upstreamFor(IdeProxyPath path, String username) {
        if (username == null) {
            return Optional.empty();
        }
        if (!worktrees.allWorktreeIds(path.projectId(), username).contains(path.consoleId())) {
            return Optional.empty();
        }
        return codeServerService.upstream(path.consoleId());
    }
}
