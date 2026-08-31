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
 *
 * <p>#342 widens ADR-102's carve-out one step further: once a worktree is actually
 * removed, its local {@code wip/<id>-<slug>} branch would otherwise survive forever
 * (ADR-005) with nobody ever prompted to clean it up (the same "nobody is ever
 * prompted" reasoning ADR-102 applied to the worktree itself) — see ADR-103. {@link
 * #removeWorktree} attempts {@code git branch -d} (never {@code -D}, never retried)
 * on that branch immediately after the worktree is gone: git's own merge check is the
 * only judgment made, so a shipped branch goes and an unmerged one silently survives.
 *
 * <p>#339/ADR-104 adds a second, distinct carve-out alongside this one, for a
 * worktree-creation path this class's original guard was never written for: a
 * project console (no issue of its own, {@link ProjectConsoleService}). {@link
 * #allProjectConsoleWorktrees()}/{@link #removalRefusalReasonForProjectConsole}/
 * {@link #removeProjectConsoleWorktree} are that second guard's whole shape — session
 * ended, HEAD detached, clean, and HEAD an ancestor of {@code origin/main} (a
 * detached worktree has no branch to preserve a commit once its reflog is deleted
 * with it, unlike the per-issue case above) — checked and removed the same
 * all-or-nothing way, by {@link #sweep()} as the periodic backstop and by {@link
 * ProjectConsoleService#close(long, String, String)} synchronously on tab close.
 * Discovery ({@link #allProjectConsoleWorktrees()}) is deliberately git-native
 * ({@code git worktree list --porcelain}, cross-referenced against the
 * sibling-directory naming convention {@link
 * ProjectConsoleService#startWorktreeSession} already uses), never from {@link
 * WorktreeSessionRepository}: a project console's tab-close already deletes its
 * persisted record unconditionally as part of ending the session, so a worktree this
 * guard refuses to remove would otherwise have no record left to find it by — and
 * asking git itself, rather than trusting directory names alone, means a same-named
 * but unrelated directory is never mistaken for a real project-console worktree.
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
     * Evaluates every console-created worktree once — per-issue and project-console
     * alike, each against its own guard — and removes each one the applicable guard
     * clears. Returns the worktree ids actually removed, so a caller (a test, or
     * #320's on-demand trigger) can report what happened rather than only that the
     * method ran.
     */
    public List<String> sweep() {
        List<String> removed = new ArrayList<>();
        for (IssueWorktreeService.ConsoleWorktree worktree : issueWorktreeService.allIssueWorktrees()) {
            if (isSafeToRemove(worktree) && removeWorktree(worktree)) {
                removed.add(worktree.worktreeId());
            }
        }
        for (ProjectConsoleWorktree worktree : allProjectConsoleWorktrees()) {
            if (removalRefusalReasonForProjectConsole(worktree).isEmpty() && removeProjectConsoleWorktree(worktree)) {
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
        // Read the branch before the worktree disappears -- there is nothing left to
        // ask "what branch is this?" once the directory is gone. A detached HEAD (or
        // a failed read) means there is no branch to delete, not an error (#342: a
        // per-issue worktree may start detached once #340 lands).
        Optional<String> branch = currentBranch(worktree.workingDirectory());
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
        // ADR-103: the branch goes the same way the worktree just did, but only when
        // git itself considers it safe -- "-d", never "-D", and no retry on refusal.
        // Branches live in the shared repo, not per-worktree, so this runs against
        // the project's own checkout, which the worktree removal above never touches.
        branch.ifPresent(name -> run(project.get().workareaPath(), "git", "branch", "-d", name));
        return true;
    }

    /**
     * The branch currently checked out in {@code workingDirectory}, empty when HEAD is
     * detached ({@code git rev-parse --abbrev-ref HEAD} reports the literal {@code
     * HEAD}) or the read fails for any reason -- either way, "no branch to delete".
     */
    private Optional<String> currentBranch(Path workingDirectory) {
        return run(workingDirectory, "git", "rev-parse", "--abbrev-ref", "HEAD")
                .map(String::strip)
                .filter(name -> !name.isEmpty() && !"HEAD".equals(name));
    }

    /**
     * Every project-console worktree git itself considers registered to a project's
     * repository, across every project (#339) — {@code git worktree list --porcelain}
     * in each project's own checkout, cross-referenced against the sibling-directory
     * naming convention {@link ProjectConsoleService#startWorktreeSession} already
     * uses ({@code <repoName>-console-<suffix>}), never from {@link
     * WorktreeSessionRepository}: see this class's javadoc for why a DB-record-based
     * discovery would be wrong here. Asking git, rather than only matching directory
     * names on disk, is deliberate: a same-named but unrelated directory (a manual
     * backup, a stray clone parked next to the project) is never mistaken for a real
     * project-console worktree, because it was never actually registered as one of
     * this repository's linked worktrees. A project not found, or with no matching
     * registered worktree, simply contributes nothing.
     */
    public List<ProjectConsoleWorktree> allProjectConsoleWorktrees() {
        List<ProjectConsoleWorktree> result = new ArrayList<>();
        for (ProjectRecord project : projectRepository.findAll()) {
            Path projectRoot = project.workareaPath();
            if (!Files.isDirectory(projectRoot)) {
                continue;
            }
            String prefix = WorktreeCreationService.repoName(projectRoot) + "-console-";
            for (Path worktreePath : registeredWorktreePaths(projectRoot)) {
                Path fileName = worktreePath.getFileName();
                if (fileName == null || !fileName.toString().startsWith(prefix)) {
                    continue;
                }
                String suffix = fileName.toString().substring(prefix.length());
                String worktreeId = project.id() + "-console-" + suffix;
                result.add(new ProjectConsoleWorktree(project.id(), worktreeId, worktreePath));
            }
        }
        return result;
    }

    /**
     * Every worktree path {@code git worktree list --porcelain} reports for the
     * repository rooted at {@code projectRoot} — one {@code worktree <path>} line per
     * entry (blank-line separated; {@code HEAD}/{@code branch}/{@code detached}/etc.
     * lines are irrelevant here and skipped). Empty when the command itself fails —
     * treated the same as "nothing registered," never guessed from disk state.
     */
    private List<Path> registeredWorktreePaths(Path projectRoot) {
        Optional<String> output = run(projectRoot, "git", "worktree", "list", "--porcelain");
        if (output.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String line : output.get().split("\n")) {
            if (line.startsWith("worktree ")) {
                paths.add(Path.of(line.substring("worktree ".length()).strip()));
            }
        }
        return paths;
    }

    /**
     * Empty when {@code worktree} clears every one of this second guard's four
     * conditions (#339/ADR-104), checked fresh, in the order stated there; otherwise
     * the reason the first failing one refuses, worded for a human reading it on the
     * project page rather than a log line — the same {@link #removalRefusalReason}
     * pattern the per-issue guard already established, applied to a worktree-creation
     * path that guard was never written for.
     */
    public Optional<String> removalRefusalReasonForProjectConsole(ProjectConsoleWorktree worktree) {
        if (sessionRegistry.hasLiveSessionIn(worktree.workingDirectory())) {
            return Optional.of("a console session is still attached to this worktree — close it before removing the worktree");
        }
        if (currentBranch(worktree.workingDirectory()).isPresent()) {
            return Optional.of("a branch is checked out in this worktree — it has outgrown scratch use, so it is left alone");
        }
        if (!isClean(worktree.workingDirectory())) {
            return Optional.of("the worktree has uncommitted changes — commit or discard them before removing it");
        }
        if (!isAncestorOfOriginMain(worktree.workingDirectory())) {
            return Optional.of("this worktree has commits not yet reachable from origin/main — removing it would lose them");
        }
        return Optional.empty();
    }

    /**
     * Removes exactly this project-console worktree's directory (via {@code git
     * worktree remove}, no {@code --force}) and forgets its persisted session record,
     * if any is still left — the ordinary case is that it is already gone, tab-close
     * having deleted it as part of ending the session; {@link SessionRegistry#close}
     * is a documented no-op for an id with nothing live or recorded, so calling it
     * unconditionally here is safe. No branch-delete step (contrast {@link
     * #removeWorktree}): this guard already refuses any worktree with a branch
     * checked out, so there is never one left to delete. Callers must have already
     * confirmed {@link #removalRefusalReasonForProjectConsole} is empty; this method
     * performs no guard check of its own.
     */
    public boolean removeProjectConsoleWorktree(ProjectConsoleWorktree worktree) {
        Optional<ProjectRecord> project = projectRepository.findById(worktree.projectId());
        if (project.isEmpty()) {
            return false;
        }
        Optional<String> output = run(project.get().workareaPath(), "git", "worktree", "remove",
                worktree.workingDirectory().toString());
        if (output.isEmpty()) {
            return false;
        }
        sessionRegistry.close(worktree.worktreeId());
        return true;
    }

    /**
     * Whether {@code workingDirectory}'s HEAD commit is reachable from a freshly
     * fetched {@code origin/main} — the one guard condition #339/ADR-104 needed that
     * ADR-102's per-issue guard never did: a detached worktree has no branch to
     * preserve a commit once the worktree (and its reflog) is removed, unlike a
     * per-issue worktree's named branch. Fetches first so a stale local
     * {@code origin/main} never says yes to a commit that has since been judged not
     * reachable; any failure along the way (no HEAD, fetch or merge-base failing) is
     * treated as "not an ancestor" — the safe direction, since it only ever keeps a
     * worktree around longer, never removes one it shouldn't.
     */
    private boolean isAncestorOfOriginMain(Path workingDirectory) {
        run(workingDirectory, "git", "fetch", "--prune", "origin");
        Optional<String> head = run(workingDirectory, "git", "rev-parse", "HEAD").map(String::strip);
        if (head.isEmpty()) {
            return false;
        }
        return run(workingDirectory, "git", "merge-base", "--is-ancestor", head.get(), "origin/main").isPresent();
    }

    /** One project-console worktree (#339) — no issue of its own, unlike {@link IssueWorktreeService.ConsoleWorktree}. */
    public record ProjectConsoleWorktree(long projectId, String worktreeId, Path workingDirectory) {
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
