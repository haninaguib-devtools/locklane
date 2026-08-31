package dev.locklane.engine.persistence;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Deletes everything a user owned (#240, ADR-101 Decision 4): their projects, those
 * projects' on-disk workarea checkouts, and any worktree/console sessions scoped to
 * them. The user row itself is deleted last, by the caller
 * ({@link dev.locklane.engine.security.AdminUserController}), once every owned project
 * is confirmed gone — nothing here touches the {@code users} table.
 *
 * <p>Reuses {@link ProjectCheckoutService#forceDelete}, the same removal
 * {@link ProjectCheckoutService#delete} does for a single project, minus that path's
 * refusal on an open worktree/console session — deleting those sessions along with
 * the project is exactly the point when the *owner* is being removed, not a reason to
 * stop.
 *
 * <p>Not wrapped in a database transaction — nothing in this codebase is (there is no
 * {@code PlatformTransactionManager} wired up anywhere). Each owned project is instead
 * deleted in full (its sessions, its own DB row, then its on-disk checkout) before
 * moving on to the next, so a failure partway through (e.g. a DB error deleting project
 * N of M) leaves the already-deleted projects gone and the rest untouched, with the
 * user row itself still present — the caller only deletes that row after this method
 * returns normally. Retrying the same user-delete call afterwards resumes cleanly: it
 * is safe to call {@link ProjectCheckoutService#forceDelete} again for an already-gone
 * project (a no-op) or for one that is still there, since {@link #deleteEverythingOwnedBy}
 * simply re-lists whatever the user still owns.
 */
@Service
public class UserCascadeDeleteService {

    private final ProjectRepository projectRepository;
    private final ProjectCheckoutService checkoutService;

    public UserCascadeDeleteService(ProjectRepository projectRepository, ProjectCheckoutService checkoutService) {
        this.projectRepository = projectRepository;
        this.checkoutService = checkoutService;
    }

    /** Deletes every project {@code ownerUserId} owns, and everything scoped to each one. */
    public void deleteEverythingOwnedBy(long ownerUserId) {
        List<ProjectRecord> owned = projectRepository.findAllOwnedBy(ownerUserId);
        for (ProjectRecord project : owned) {
            checkoutService.forceDelete(project.id());
        }
    }
}
