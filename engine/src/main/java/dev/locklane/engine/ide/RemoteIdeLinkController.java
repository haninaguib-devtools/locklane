package dev.locklane.engine.ide;

import dev.locklane.engine.persistence.IssueWorktreeService;
import dev.locklane.engine.persistence.ProjectIdeSessionService;
import dev.locklane.engine.pty.SessionRegistry;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a browser away from the engine's own machine needs to open a session's worktree
 * in a desktop IDE running on the <em>browser's</em> machine over SSH (#949): the OS
 * user the engine runs as, the session's absolute worktree path, the SSH port, and the
 * optional JetBrains Gateway settings. The client builds the {@code vscode://} or
 * {@code jetbrains-gateway://} link itself from these plus its own page hostname; the
 * engine launches nothing and never learns which link was opened.
 *
 * <p>Sibling of {@link InstalledIdesController} under {@code /api/ides/**} (authenticated
 * in {@code SecurityConfig}). The session is looked up with the same visibility rule
 * {@code AgentSessionsController#openIde} applies -- a session outside the caller's own
 * project, or a project's main-checkout IDE session not open for them, is a 404, as is
 * one with no known working directory -- so this never leaks a path the caller could
 * not open code-server on.
 */
@RestController
@RequestMapping("/api/ides")
public class RemoteIdeLinkController {

    private static final Logger log = LoggerFactory.getLogger(RemoteIdeLinkController.class);

    private final IssueWorktreeService worktreeService;
    private final ProjectIdeSessionService projectIdeSessionService;
    private final SessionRegistry sessionRegistry;
    private final String user;
    private final int sshPort;
    private final Gateway gateway;

    @Autowired
    public RemoteIdeLinkController(IssueWorktreeService worktreeService,
            ProjectIdeSessionService projectIdeSessionService, SessionRegistry sessionRegistry,
            @Value("${locklane.remote-ide.ssh-port:22}") int sshPort,
            @Value("${locklane.remote-ide.gateway.product-code:}") String gatewayProductCode,
            @Value("${locklane.remote-ide.gateway.build-number:}") String gatewayBuildNumber,
            @Value("${locklane.remote-ide.gateway.ide-path:}") String gatewayIdePath) {
        this(worktreeService, projectIdeSessionService, sessionRegistry, System.getProperty("user.name", ""), sshPort,
                new Gateway(blankToNull(gatewayProductCode), blankToNull(gatewayBuildNumber), blankToNull(gatewayIdePath)));
    }

    RemoteIdeLinkController(IssueWorktreeService worktreeService, ProjectIdeSessionService projectIdeSessionService,
            SessionRegistry sessionRegistry, String user, int sshPort, Gateway gateway) {
        this.worktreeService = worktreeService;
        this.projectIdeSessionService = projectIdeSessionService;
        this.sessionRegistry = sessionRegistry;
        this.user = user;
        this.sshPort = sshPort;
        this.gateway = gateway;
    }

    /**
     * The facts for {@code session} (an agent, shell or main-checkout IDE session id under
     * {@code project}), or 404 when the caller may not see it or its worktree is unknown.
     */
    @GetMapping("/remote-link")
    public ResponseEntity<RemoteIdeLink> remoteLink(@RequestParam long project, @RequestParam String session,
            Principal principal) {
        boolean visible = worktreeService.allWorktreeIds(project, principal.getName()).contains(session)
                || projectIdeSessionService.isOpenAndVisibleTo(project, session, principal.getName());
        if (!visible) {
            log.warn("remote-link refused: session {} is not visible to {} under project {}", session, principal.getName(), project);
            return ResponseEntity.notFound().build();
        }
        Optional<Path> workingDirectory = sessionRegistry.lastKnownWorkingDirectory(session);
        if (workingDirectory.isEmpty()) {
            log.warn("remote-link refused: session {} has no known working directory", session);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new RemoteIdeLink(user, sshPort, workingDirectory.get().toAbsolutePath().toString(), gateway));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The answer: {@code user} the engine's OS user for {@code ssh user@host}, {@code sshPort}
     * the port to dial, {@code path} the session's absolute worktree, and the Gateway
     * settings -- each {@code null} when unset, the client picks the deploy variant from them.
     */
    public record RemoteIdeLink(String user, int sshPort, String path, Gateway gateway) {
    }

    /** Optional JetBrains Gateway settings: a product code and build number to deploy, or a remote IDE path already installed. */
    public record Gateway(String productCode, String buildNumber, String idePath) {
    }
}
