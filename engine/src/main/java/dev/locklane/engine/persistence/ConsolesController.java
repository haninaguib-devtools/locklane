package dev.locklane.engine.persistence;

import dev.locklane.engine.codeserver.CodeServerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Optional;

/**
 * Serves every open console session across all of one project's issues, plus its
 * own project-level consoles (#194), that the caller may see (#32's header
 * indicator/picker) — same ownership visibility as {@link WorktreeController#worktrees},
 * just not scoped to one issue. Nested under a project id since #43.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/consoles")
public class ConsolesController {

    private final IssueWorktreeService service;
    private final FileManagerLauncher fileManagerLauncher;
    private final CodeServerService codeServerService;

    public ConsolesController(IssueWorktreeService service, FileManagerLauncher fileManagerLauncher,
            CodeServerService codeServerService) {
        this.service = service;
        this.fileManagerLauncher = fileManagerLauncher;
        this.codeServerService = codeServerService;
    }

    @GetMapping
    public List<String> consoles(@PathVariable long projectId, Principal principal) {
        return service.allWorktreeIds(projectId, principal.getName());
    }

    /**
     * Reveals {@code id}'s worktree in the local OS's file manager (#441) — same
     * visibility rule as {@link #consoles}, so this can't be used to reveal a console
     * outside the caller's own project. 404 for a console id the caller may not see,
     * or one with no known working directory (never closed but never actually
     * attached to, or already closed).
     */
    @PostMapping("/{id}/reveal-in-file-manager")
    public ResponseEntity<Void> reveal(@PathVariable long projectId, @PathVariable String id, Principal principal) {
        if (!service.allWorktreeIds(projectId, principal.getName()).contains(id)) {
            return ResponseEntity.notFound().build();
        }
        return fileManagerLauncher.reveal(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Starts (or reuses) a code-server (#627) process for {@code id}'s worktree and
     * returns the URL to open it at — same visibility rule as {@link #consoles}, so
     * this can't be used to open an editor on a console outside the caller's own
     * project. 404 for a console id the caller may not see, or one with no known
     * working directory.
     *
     * <p>The URL is the engine's own proxied path for that console (#655),
     * {@code /api/projects/{projectId}/consoles/{id}/ide/}, relative so it resolves
     * against whatever host the browser reached locklane at — never the loopback
     * address code-server itself listens on, which a remote browser could not open. It
     * carries the console's own worktree as a {@code folder} query parameter (#776):
     * code-server otherwise reopens whichever folder was last opened by *any* console
     * on this machine (they share one user data directory) once a request arrives with
     * no `folder` of its own, e.g. a bookmark or a bare refresh —
     * {@code --ignore-last-opened} on the command line ({@link CodeServerService})
     * closes that path off too, so this query parameter is what a fresh request relies
     * on.
     */
    @PostMapping("/{id}/open-ide")
    public ResponseEntity<OpenIdeResponse> openIde(@PathVariable long projectId, @PathVariable String id, Principal principal) {
        if (!service.allWorktreeIds(projectId, principal.getName()).contains(id)) {
            return ResponseEntity.notFound().build();
        }
        Optional<URI> upstream = codeServerService.start(id);
        if (upstream.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path workingDirectory = codeServerService.workingDirectory(id)
                .orElseThrow(() -> new IllegalStateException("code-server for " + id + " started with no tracked working directory"));
        return ResponseEntity.ok(new OpenIdeResponse(ideUrl(projectId, id, workingDirectory)));
    }

    /**
     * The proxied path a browser opens {@code id}'s IDE at (#655), slash-terminated so
     * its relative links resolve inside it, carrying {@code folder} so it opens on
     * {@code id}'s own worktree rather than another console's (#776).
     */
    static String ideUrl(long projectId, String id, Path folder) {
        String encodedFolder = URLEncoder.encode(folder.toString(), StandardCharsets.UTF_8);
        return "/api/projects/" + projectId + "/consoles/" + id + "/ide/?folder=" + encodedFolder;
    }

    public record OpenIdeResponse(String url) {}
}
