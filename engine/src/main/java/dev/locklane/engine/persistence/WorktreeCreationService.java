package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhIssueCache;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Starts a new agent session for an issue that has none yet, so the client (#20)
 * never has to guess a working directory itself. Mirrors what {@code /t-wtree}
 * already does for the human-facing pipeline — a real {@code git worktree add} on a
 * {@code wip/<id>-<slug>} branch, in a sibling {@code ../<repo-name>-<id>} checkout
 * — chosen over a lighter-weight non-git convention because per-worktree isolation
 * is the whole point of this app's agent model (ADR-002); a session that silently
 * reused the main checkout would undermine that. See the task record for the full
 * reasoning.
 *
 * <p>Since #43, the checkout a session is created against is resolved per project
 * (each project's own workarea, from {@link ProjectRepository}) rather than a
 * single fixed root — issue data itself stays global (a separate, deferred
 * concern; see #43's task record), but where a worktree lives on disk always
 * follows the project id in the request.
 */
@Service
public class WorktreeCreationService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_SLUG_LENGTH = 40;

    private final GhIssueCache issueCache;
    private final IssueWorktreeService issueWorktreeService;
    private final ProjectRepository projectRepository;

    public WorktreeCreationService(GhIssueCache issueCache, IssueWorktreeService issueWorktreeService,
            ProjectRepository projectRepository) {
        this.issueCache = issueCache;
        this.issueWorktreeService = issueWorktreeService;
        this.projectRepository = projectRepository;
    }

    /**
     * Starts (or, if one already exists, simply reports) the one worktree session
     * for a project's issue, including its working directory — a brand new worktree
     * has no persisted session yet (nothing has attached to it via WebSocket to
     * record one, #6/#15), so the client cannot resolve that directory on its own
     * the way it can for an already-known worktree; it must be told directly, to
     * pass as {@code ?dir=} on the first attach (#7). Empty if the project or the
     * issue itself is not known.
     *
     * <p>Equivalent to {@code startSession(projectId, issueNumber, true, null)} —
     * kept for existing callers that only ever want a worktree.
     */
    public Optional<StartedSession> startSession(long projectId, int issueNumber) {
        return startSession(projectId, issueNumber, true, null);
    }

    /**
     * As above, but a console can also be opened directly against the project's
     * main checkout: when {@code useWorktree} is {@code false}, no
     * {@code git worktree add} runs and no worktree needs to exist — the working
     * directory is simply the project's checkout, and a fresh session id is minted
     * so several such consoles (including several on the main checkout, #29) can
     * coexist for the same project's issue.
     */
    public Optional<StartedSession> startSession(long projectId, int issueNumber, boolean useWorktree) {
        return startSession(projectId, issueNumber, useWorktree, null);
    }

    /**
     * As above, but only an existing session {@code requestingUsername} may see
     * (their own, or one with no recorded owner, #48) is reused — a session another
     * user owns is invisible here, same as it is in {@link IssueWorktreeService}.
     */
    public Optional<StartedSession> startSession(long projectId, int issueNumber, boolean useWorktree,
            String requestingUsername) {
        Optional<ProjectRecord> project = projectRepository.findById(projectId);
        if (project.isEmpty() || project.get().status() != ProjectStatus.READY) {
            return Optional.empty();
        }
        Path projectRoot = project.get().workareaPath();

        if (!useWorktree) {
            if (issueCache.issue(issueNumber).isEmpty()) {
                return Optional.empty();
            }
            String sessionId = projectId + "-" + issueNumber + "-main-" + shortId();
            return Optional.of(new StartedSession(sessionId, projectRoot.toString()));
        }

        Path worktreePath = projectRoot.resolveSibling(repoName(projectRoot) + "-" + issueNumber);

        // Excludes main-checkout session ids (shaped "<projectId>-<issueNumber>-main-<suffix>",
        // minted just above): they match the project/issue prefix but were never a worktree.
        String mainSessionPrefix = projectId + "-" + issueNumber + "-main-";
        List<String> existing = issueWorktreeService.worktreeIdsForIssue(projectId, issueNumber, requestingUsername)
                .stream()
                .filter(id -> !id.startsWith(mainSessionPrefix))
                .toList();
        if (!existing.isEmpty()) {
            return Optional.of(new StartedSession(existing.get(0), worktreePath.toString()));
        }

        Optional<GhIssue> issue = issueCache.issue(issueNumber);
        if (issue.isEmpty()) {
            return Optional.empty();
        }

        String slug = slug(issue.get().title());
        String worktreeId = projectId + "-" + issueNumber + "-" + slug;
        String branch = "wip/" + issueNumber + "-" + slug;

        if (!Files.exists(worktreePath)) {
            createWorktree(branch, worktreePath, projectRoot);
        }
        return Optional.of(new StartedSession(worktreeId, worktreePath.toString()));
    }

    private static String shortId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public record StartedSession(String worktreeId, String workingDirectory) {
    }

    private void createWorktree(String branch, Path worktreePath, Path projectRoot) {
        run("git", "-C", projectRoot.toString(), "fetch", "--prune", "origin");

        boolean branchExists =
                run("git", "-C", projectRoot.toString(), "rev-parse", "--verify", "--quiet", branch).exitCode() == 0
                || run("git", "-C", projectRoot.toString(), "rev-parse", "--verify", "--quiet", "origin/" + branch)
                        .exitCode() == 0;

        ProcessResult result = branchExists
                ? run("git", "-C", projectRoot.toString(), "worktree", "add", worktreePath.toString(), branch)
                : run("git", "-C", projectRoot.toString(), "worktree", "add", "-b", branch,
                        worktreePath.toString(), "origin/main");

        if (result.exitCode() != 0) {
            throw new WorktreeCreationException(
                    "git worktree add failed for branch '" + branch + "': " + result.stderr().strip());
        }
    }

    private static String repoName(Path projectRoot) {
        Path name = projectRoot.getFileName();
        return name != null ? name.toString() : "repo";
    }

    /** Same shape as the wip/&lt;id&gt;-&lt;slug&gt; branch convention (AGENTS.md). */
    static String slug(String title) {
        String lower = title.toLowerCase();
        String dashed = NON_ALNUM.matcher(lower).replaceAll("-");
        String trimmed = dashed.replaceAll("^-+", "").replaceAll("-+$", "");
        String truncated = trimmed.length() > MAX_SLUG_LENGTH ? trimmed.substring(0, MAX_SLUG_LENGTH) : trimmed;
        return truncated.replaceAll("-+$", "");
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
            throw new WorktreeCreationException("Interrupted while running git", e);
        } catch (IOException e) {
            throw new WorktreeCreationException("Could not run git — is it installed and on PATH?", e);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    public static class WorktreeCreationException extends RuntimeException {
        public WorktreeCreationException(String message) {
            super(message);
        }

        public WorktreeCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
