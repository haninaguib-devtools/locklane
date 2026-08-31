package dev.locklane.engine.persistence;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Creates projects and clones them into their own workarea directory (#42), off the
 * request thread — {@code cloneExecutor} runs the actual {@code git clone} (a
 * virtual-thread executor in production; tests inject a same-thread one so the
 * outcome is asserted without polling).
 */
@Service
public class ProjectCheckoutService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final ProjectRepository repository;
    private final Path workareaRoot;
    private final Executor cloneExecutor;
    private final IssueWorktreeService issueWorktreeService;

    public ProjectCheckoutService(ProjectRepository repository,
            @Value("${locklane.workarea-root}") String workareaRoot,
            @Qualifier("projectCloneExecutor") Executor cloneExecutor,
            IssueWorktreeService issueWorktreeService) {
        this.repository = repository;
        this.workareaRoot = Path.of(workareaRoot).normalize();
        this.cloneExecutor = cloneExecutor;
        this.issueWorktreeService = issueWorktreeService;
    }

    /**
     * Persists a new project in {@link ProjectStatus#CLONING} and starts cloning it
     * asynchronously. {@code requestedName} blank/{@code null} derives a name from
     * {@code gitUrl}; the workarea directory name is derived from the (derived or
     * given) name, disambiguated with a numeric suffix on collision. {@code ownerUserId}
     * (#239) is the authenticated caller creating the project — the workarea lands
     * under {@code workareas/<ownerUserId>/<slug>} (ADR-101 Decision 2), organizational
     * only, never itself the authorization boundary.
     */
    public ProjectRecord createProject(String gitUrl, String requestedName, long ownerUserId) {
        String trimmedUrl = gitUrl.strip();
        String name = (requestedName == null || requestedName.isBlank())
                ? deriveName(trimmedUrl) : requestedName.strip();
        Path workareaPath = uniqueWorkareaPath(ownerUserId, slug(name));

        ProjectRecord project = repository.create(name, trimmedUrl, workareaPath, ownerUserId, Instant.now());
        cloneExecutor.execute(() -> clone(project));
        return project;
    }

    /** Re-clones a {@link ProjectStatus#FAILED} project from scratch. Empty if it doesn't exist or isn't failed. */
    public Optional<ProjectRecord> retry(long id) {
        Optional<ProjectRecord> existing = repository.findById(id);
        if (existing.isEmpty() || existing.get().status() != ProjectStatus.FAILED) {
            return Optional.empty();
        }
        deleteDirectoryQuietly(existing.get().workareaPath());
        repository.markCloning(id);
        ProjectRecord cloning = repository.findById(id).orElseThrow();
        cloneExecutor.execute(() -> clone(cloning));
        return Optional.of(cloning);
    }

    /**
     * Forgets the project and best-effort removes its workarea directory — refusing
     * (#231) when any worktree or console session is still open for it, so deleting
     * never orphans one out from under whoever is attached to it.
     */
    public DeleteOutcome delete(long id) {
        Optional<ProjectRecord> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return DeleteOutcome.NOT_FOUND;
        }
        if (issueWorktreeService.hasAnySessions(id)) {
            return DeleteOutcome.HAS_OPEN_SESSIONS;
        }
        repository.delete(id);
        deleteDirectoryQuietly(existing.get().workareaPath());
        return DeleteOutcome.DELETED;
    }

    public enum DeleteOutcome {
        NOT_FOUND, HAS_OPEN_SESSIONS, DELETED
    }

    /**
     * Unconditionally deletes a project and everything scoped to it: any worktree or
     * console sessions, its DB row, and its on-disk workarea checkout (best-effort, same
     * as {@link #delete}) — never refuses on an open session the way {@link #delete}
     * does. Only {@link UserCascadeDeleteService} calls this (#240, ADR-101 Decision 4):
     * cascade-deleting a user is exactly the case where its projects' sessions are
     * supposed to disappear along with the project, not block the delete the way they do
     * for an ordinary single-project delete. A no-op if the project is already gone, so
     * it is safe to call again after a partial failure.
     */
    public void forceDelete(long id) {
        Optional<ProjectRecord> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return;
        }
        issueWorktreeService.deleteSessionsForProject(id);
        repository.delete(id);
        deleteDirectoryQuietly(existing.get().workareaPath());
    }

    private void clone(ProjectRecord project) {
        try {
            Files.createDirectories(project.workareaPath().getParent());
            ProcessResult cloneResult = run("git", "clone", project.gitUrl(), project.workareaPath().toString());
            if (cloneResult.exitCode() != 0) {
                repository.markFailed(project.id());
                return;
            }
            ProcessResult branchResult =
                    run("git", "-C", project.workareaPath().toString(), "branch", "--show-current");
            String branch = branchResult.stdout().strip();
            if (branchResult.exitCode() != 0 || branch.isBlank()) {
                repository.markFailed(project.id());
                return;
            }
            repository.markReady(project.id(), branch);
        } catch (RuntimeException | IOException e) {
            repository.markFailed(project.id());
        }
    }

    private Path uniqueWorkareaPath(long ownerUserId, String slug) {
        Path ownerRoot = workareaRoot.resolve(String.valueOf(ownerUserId));
        Path candidate = ownerRoot.resolve(slug);
        int suffix = 2;
        while (Files.exists(candidate) || repository.findByWorkareaPath(candidate).isPresent()) {
            candidate = ownerRoot.resolve(slug + "-" + suffix++);
        }
        return candidate;
    }

    private static void deleteDirectoryQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup — a leftover file here doesn't block anything.
                }
            });
        } catch (IOException ignored) {
            // Same: cleanup is best-effort.
        }
    }

    static String deriveName(String gitUrl) {
        String trimmed = gitUrl.replaceAll("/+$", "");
        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        String tail = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        return tail.endsWith(".git") ? tail.substring(0, tail.length() - 4) : tail;
    }

    /** Same shape as {@code WorktreeCreationService.slug}, minus the length cap (directory names, not branches). */
    static String slug(String name) {
        String lower = name.toLowerCase();
        String dashed = NON_ALNUM.matcher(lower).replaceAll("-");
        String trimmed = dashed.replaceAll("^-+", "").replaceAll("-+$", "");
        return trimmed.isEmpty() ? "project" : trimmed;
    }

    private static ProcessResult run(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new ProcessResult(exit, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProjectCheckoutException("Interrupted while running git", e);
        } catch (IOException e) {
            throw new ProjectCheckoutException("Could not run git — is it installed and on PATH?", e);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    public static class ProjectCheckoutException extends RuntimeException {
        public ProjectCheckoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
