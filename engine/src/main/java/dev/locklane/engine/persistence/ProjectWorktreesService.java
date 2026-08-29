package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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

    /** Every worktree belonging to one of this project's issues, in no particular order. */
    public List<WorktreeRow> listForProject(long projectId) {
        return worktreeService.allIssueWorktrees().stream()
                .filter(worktree -> worktree.projectId() == projectId)
                .map(worktree -> new WorktreeRow(worktree.worktreeId(), worktree.issueNumber(),
                        worktree.workingDirectory().toString(), sweeper.isClean(worktree.workingDirectory()),
                        sessionRegistry.hasLiveSessionIn(worktree.workingDirectory())))
                .toList();
    }

    /**
     * Removes one worktree by id, scoped to {@code projectId} so a request naming
     * another project's worktree id is treated the same as an unknown one. Uses the
     * exact guard {@link WorktreeCleanupSweeper#sweep()} uses ({@link
     * WorktreeCleanupSweeper#removalRefusalReason}) before removing.
     */
    public RemovalResult remove(long projectId, String worktreeId) {
        Optional<IssueWorktreeService.ConsoleWorktree> worktree = worktreeService.allIssueWorktrees().stream()
                .filter(w -> w.projectId() == projectId && w.worktreeId().equals(worktreeId))
                .findFirst();
        if (worktree.isEmpty()) {
            return RemovalResult.notFound();
        }
        Optional<String> refusal = sweeper.removalRefusalReason(worktree.get());
        if (refusal.isPresent()) {
            return RemovalResult.refused(refusal.get());
        }
        if (!sweeper.removeWorktree(worktree.get())) {
            return RemovalResult.refused("failed to remove the worktree — see the server log");
        }
        return RemovalResult.succeeded();
    }

    /** Triggers the same sweep the schedule runs, on demand. Returns the worktree ids it actually removed. */
    public List<String> runCleanupNow() {
        return sweeper.sweep();
    }

    /** One row of {@link #listForProject}. */
    public record WorktreeRow(String worktreeId, int issueNumber, String workingDirectory, boolean clean,
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
