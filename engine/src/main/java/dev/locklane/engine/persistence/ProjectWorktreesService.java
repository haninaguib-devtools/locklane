package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The project page's worktree list (#320): every console-created worktree for one
 * project's issues, with the clean/dirty and session-attached status a human needs to
 * judge each row, plus the manual "remove worktree" action and the on-demand "run
 * cleanup now" trigger. Deliberately thin — {@link WorktreeCleanupSweeper} already
 * owns the whole of the removal guard and the sweep itself (#319); this service reuses
 * both rather than re-deriving either, so the manual path and the periodic one can
 * never quietly drift apart.
 *
 * <p>"Run cleanup now" calls {@link WorktreeCleanupSweeper#sweep()} unmodified — the
 * same system-wide sweep the schedule runs, not a project-scoped variant. Scoping it
 * to one project would mean either a second sweep method (a second copy of the guard)
 * or filtering {@link WorktreeCleanupSweeper#sweep()}'s own loop from the outside,
 * which it exposes no way to do; calling the identical method keeps the guard singular
 * at the cost of a project's cleanup button being able to remove another project's
 * eligible worktree too — no less safe, since the guard is unchanged, just wider in
 * scope than the button's own page.
 *
 * <p>Since #339, a project-console worktree (no issue of its own) appears in {@link
 * #listForProject} too, with a {@code null} {@link WorktreeRow#issueNumber()} — {@link
 * WorktreeCleanupSweeper#allProjectConsoleWorktrees()} discovers those the same
 * git-native way the sweep itself does, never from a persisted session record. {@link
 * #remove} dispatches to whichever guard applies (issue-closed vs. detached +
 * ancestor-of-{@code origin/main}) purely by which of the two collections the id
 * appears in, so the manual "remove" button and the two automatic removal paths
 * (tab-close, sweep) all ultimately ask the same question for the same worktree.
 */
@Service
public class ProjectWorktreesService {

    private final IssueWorktreeService worktreeService;
    private final WorktreeCleanupSweeper sweeper;
    private final SessionRegistry sessionRegistry;

    public ProjectWorktreesService(IssueWorktreeService worktreeService, WorktreeCleanupSweeper sweeper,
            SessionRegistry sessionRegistry) {
        this.worktreeService = worktreeService;
        this.sweeper = sweeper;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Every worktree belonging to one of this project's issues, plus every one of its
     * project-console worktrees (#339), in no particular order. A console row's
     * {@link WorktreeRow#issueNumber()} is {@code null} — it has no issue to report.
     */
    public List<WorktreeRow> listForProject(long projectId) {
        Stream<WorktreeRow> issueRows = worktreeService.allIssueWorktrees().stream()
                .filter(worktree -> worktree.projectId() == projectId)
                .map(worktree -> new WorktreeRow(worktree.worktreeId(), worktree.issueNumber(),
                        worktree.workingDirectory().toString(), sweeper.isClean(worktree.workingDirectory()),
                        sessionRegistry.hasLiveSessionIn(worktree.workingDirectory())));
        Stream<WorktreeRow> consoleRows = sweeper.allProjectConsoleWorktrees().stream()
                .filter(worktree -> worktree.projectId() == projectId)
                .map(worktree -> new WorktreeRow(worktree.worktreeId(), null,
                        worktree.workingDirectory().toString(), sweeper.isClean(worktree.workingDirectory()),
                        sessionRegistry.hasLiveSessionIn(worktree.workingDirectory())));
        return Stream.concat(issueRows, consoleRows).toList();
    }

    /**
     * Removes one worktree by id, scoped to {@code projectId} so a request naming
     * another project's worktree id is treated the same as an unknown one. Looks
     * among this project's per-issue worktrees first, then its project-console ones
     * (#339) — the two id shapes never collide — and applies whichever guard matches
     * ({@link WorktreeCleanupSweeper#removalRefusalReason} or {@link
     * WorktreeCleanupSweeper#removalRefusalReasonForProjectConsole}) before removing.
     */
    public RemovalResult remove(long projectId, String worktreeId) {
        Optional<IssueWorktreeService.ConsoleWorktree> issueWorktree = worktreeService.allIssueWorktrees().stream()
                .filter(w -> w.projectId() == projectId && w.worktreeId().equals(worktreeId))
                .findFirst();
        if (issueWorktree.isPresent()) {
            return removeIssueWorktree(issueWorktree.get());
        }
        Optional<WorktreeCleanupSweeper.ProjectConsoleWorktree> consoleWorktree =
                sweeper.allProjectConsoleWorktrees().stream()
                        .filter(w -> w.projectId() == projectId && w.worktreeId().equals(worktreeId))
                        .findFirst();
        if (consoleWorktree.isPresent()) {
            return removeProjectConsoleWorktree(consoleWorktree.get());
        }
        return RemovalResult.notFound();
    }

    /** Triggers the same sweep the schedule runs, on demand. Returns the worktree ids it actually removed. */
    public List<String> runCleanupNow() {
        return sweeper.sweep();
    }

    private RemovalResult removeIssueWorktree(IssueWorktreeService.ConsoleWorktree worktree) {
        Optional<String> refusal = sweeper.removalRefusalReason(worktree);
        if (refusal.isPresent()) {
            return RemovalResult.refused(refusal.get());
        }
        if (!sweeper.removeWorktree(worktree)) {
            return RemovalResult.refused("failed to remove the worktree — see the server log");
        }
        return RemovalResult.succeeded();
    }

    private RemovalResult removeProjectConsoleWorktree(WorktreeCleanupSweeper.ProjectConsoleWorktree worktree) {
        Optional<String> refusal = sweeper.removalRefusalReasonForProjectConsole(worktree);
        if (refusal.isPresent()) {
            return RemovalResult.refused(refusal.get());
        }
        if (!sweeper.removeProjectConsoleWorktree(worktree)) {
            return RemovalResult.refused("failed to remove the worktree — see the server log");
        }
        return RemovalResult.succeeded();
    }

    /**
     * One row of {@link #listForProject} — {@code issueNumber} is {@code null} for a
     * project-console worktree (#339), which has no issue of its own.
     */
    public record WorktreeRow(String worktreeId, Integer issueNumber, String workingDirectory, boolean clean,
            boolean sessionAttached) {
    }

    /** The outcome of {@link #remove} — exactly one of the three static factories below. */
    public record RemovalResult(boolean found, boolean removed, String refusalReason) {
        static RemovalResult notFound() {
            return new RemovalResult(false, false, null);
        }

        static RemovalResult refused(String reason) {
            return new RemovalResult(true, false, reason);
        }

        static RemovalResult succeeded() {
            return new RemovalResult(true, true, null);
        }
    }
}
