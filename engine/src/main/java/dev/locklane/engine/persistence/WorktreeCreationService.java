package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.GhIssueCache;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Service
public class WorktreeCreationService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_SLUG_LENGTH = 40;

    private final GhIssueCache issueCache;
    private final IssueWorktreeService issueWorktreeService;
    private final Path projectRoot;

    public WorktreeCreationService(GhIssueCache issueCache, IssueWorktreeService issueWorktreeService,
            @Value("${locklane.project-root}") String projectRoot) {
        this.issueCache = issueCache;
        this.issueWorktreeService = issueWorktreeService;
        this.projectRoot = Path.of(projectRoot).normalize();
    }

    /**
     * Starts (or, if one already exists, simply reports) the one worktree session
     * for an issue, including its working directory — a brand new worktree has no
     * persisted session yet (nothing has attached to it via WebSocket to record
     * one, #6/#15), so the client cannot resolve that directory on its own the way
     * it can for an already-known worktree; it must be told directly, to pass as
     * {@code ?dir=} on the first attach (#7). Empty if the issue itself is not
     * known.
     */
    public Optional<StartedSession> startSession(int issueNumber) {
        Path worktreePath = projectRoot.resolveSibling(repoName() + "-" + issueNumber);

        List<String> existing = issueWorktreeService.worktreeIdsForIssue(issueNumber);
        if (!existing.isEmpty()) {
            return Optional.of(new StartedSession(existing.get(0), worktreePath.toString()));
        }

        Optional<GhIssue> issue = issueCache.issue(issueNumber);
        if (issue.isEmpty()) {
            return Optional.empty();
        }

        String worktreeId = issueNumber + "-" + slug(issue.get().title());
        String branch = "wip/" + worktreeId;

        if (!Files.exists(worktreePath)) {
            createWorktree(branch, worktreePath);
        }
        return Optional.of(new StartedSession(worktreeId, worktreePath.toString()));
    }

    public record StartedSession(String worktreeId, String workingDirectory) {
    }

    private void createWorktree(String branch, Path worktreePath) {
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

    private String repoName() {
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
