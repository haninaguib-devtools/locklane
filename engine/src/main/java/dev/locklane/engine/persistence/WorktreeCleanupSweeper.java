package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.pty.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Periodically deletes a console-created worktree once it is safe to do so (#319) —
 * a deliberate, narrow exception to ADR-005's "left alone permanently, removed by
 * hand only": a worktree is removed automatically only when every one of these holds,
 * checked fresh at sweep time, and left untouched (never force-removed) otherwise:
 *
 * <ul>
 *   <li>its issue's cached state ({@link ProjectGhResources}/{@link
 *       dev.locklane.engine.github.GhIssueCache}) is {@code CLOSED} — a project or
 *       issue not found in the cache is treated the same as "not closed"</li>
 *   <li>{@code git status --porcelain} in the worktree is empty</li>
 *   <li>no live session ({@link SessionRegistry#hasLiveSessionIn}) has a working
 *       directory inside it</li>
 * </ul>
 *
 * <p>{@link #sweep()} is the whole of the guard logic, callable on its own schedule
 * below or, per this task's done-when, programmatically — #320's on-demand "run
 * cleanup now" trigger calls the same method rather than duplicating it.
 *
 * <p>{@link #removalRefusalReason} exposes the same three-part guard one worktree at a
 * time, with a human-readable reason attached to whichever check fails first — #320's
 * per-row manual "remove worktree" action calls this rather than re-deriving the
 * guard's conditions for itself, and shows the reason verbatim when it refuses.
 */
@Service
public class WorktreeCleanupSweeper {

    private static final Logger log = LoggerFactory.getLogger(WorktreeCleanupSweeper.class);
    private static final String CLOSED = "CLOSED";

    private final IssueWorktreeService issueWorktreeService;
    private final ProjectRepository projectRepository;
    private final ProjectGhResources ghResources;
    private final SessionRegistry sessionRegistry;

    @Autowired
    public WorktreeCleanupSweeper(IssueWorktreeService issueWorktreeService, ProjectRepository projectRepository,
            ProjectGhResources ghResources, SessionRegistry sessionRegistry) {
        this.issueWorktreeService = issueWorktreeService;
        this.projectRepository = projectRepository;
        this.ghResources = ghResources;
        this.sessionRegistry = sessionRegistry;
    }

    @Scheduled(fixedDelayString = "${locklane.worktree-cleanup.interval-ms}",
            initialDelayString = "${locklane.worktree-cleanup.interval-ms}")
    void scheduledSweep() {
        sweep();
    }

    /**
     * Evaluates every console-created worktree once and removes each one the guard
     * above clears — returns the worktree ids actually removed, so a caller (a test,
     * or #320's on-demand trigger) can report what happened rather than only that the
     * method ran.
     */
    public List<String> sweep() {
        List<String> removed = new ArrayList<>();
        for (IssueWorktreeService.ConsoleWorktree worktree : issueWorktreeService.allIssueWorktrees()) {
            if (isSafeToRemove(worktree) && removeWorktree(worktree)) {
                removed.add(worktree.worktreeId());
            }
        }
        return removed;
    }

    private boolean isSafeToRemove(IssueWorktreeService.ConsoleWorktree worktree) {
        return removalRefusalReason(worktree).isEmpty();
    }

    /**
     * Empty when {@code worktree} clears every one of {@link #sweep()}'s three guard
     * conditions, checked fresh, in the same order {@link #sweep()} checks them;
     * otherwise the reason the first failing one refuses, worded for a human reading
     * it on the project page (#320) rather than a log line.
     */
    public Optional<String> removalRefusalReason(IssueWorktreeService.ConsoleWorktree worktree) {
        Optional<GhIssue> issue = ghResources.forProject(worktree.projectId())
                .flatMap(ctx -> ctx.cache().issue(worktree.issueNumber()));
        if (issue.isEmpty()) {
            return Optional.of("issue #" + worktree.issueNumber() + " could not be found — refusing to remove its worktree");
        }
        if (!CLOSED.equals(issue.get().state())) {
            return Optional.of("issue #" + worktree.issueNumber() + " is still open — close it before removing its worktree");
        }
        if (!isClean(worktree.workingDirectory())) {
            return Optional.of("the worktree has uncommitted changes — commit or discard them before removing it");
        }
        if (sessionRegistry.hasLiveSessionIn(worktree.workingDirectory())) {
            return Optional.of("a console session is still attached to this worktree — close it before removing the worktree");
        }
        return Optional.empty();
    }

    /** Whether {@code git status --porcelain} in {@code workingDirectory} reports nothing outstanding. */
    public boolean isClean(Path workingDirectory) {
        if (!Files.isDirectory(workingDirectory)) {
            // Already gone, or never actually created — nothing here to remove, and
            // "clean" would be a lie: there is no git status to have checked at all.
            return false;
        }
        Optional<String> output = run(workingDirectory, "git", "status", "--porcelain");
        return output.map(String::isEmpty).orElse(false);
    }

    /**
     * Removes exactly this worktree's directory (via {@code git worktree remove}, no
     * {@code --force}) and forgets its persisted session record. Callers outside
     * {@link #sweep()} — #320's manual remove action — must have already confirmed
     * {@link #removalRefusalReason} is empty; this method itself performs no guard
     * check, only the removal, so the guard is asked exactly once per call site
     * rather than silently re-run here too.
     */
    public boolean removeWorktree(IssueWorktreeService.ConsoleWorktree worktree) {
        Optional<ProjectRecord> project = projectRepository.findById(worktree.projectId());
        if (project.isEmpty()) {
            return false;
        }
        // No --force: git itself refuses on any uncommitted/untracked state, a second,
        // independent guard alongside the git-status check above in case of a race.
        Optional<String> output = run(project.get().workareaPath(), "git", "worktree", "remove",
                worktree.workingDirectory().toString());
        if (output.isEmpty()) {
            return false;
        }
        // The directory is gone: forget its persisted session record too (and, via
        // SessionRegistry#close, broadcast the same consolesChanged event a human
        // explicitly closing it would) so nothing lists a path that no longer exists.
        sessionRegistry.close(worktree.worktreeId());
        return true;
    }

    private Optional<String> run(Path workingDirectory, String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("'{}' failed ({}) in {}: {}", String.join(" ", command), exitCode, workingDirectory,
                        output.strip());
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            log.warn("Failed to run '{}' in {}", String.join(" ", command), workingDirectory, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

}
