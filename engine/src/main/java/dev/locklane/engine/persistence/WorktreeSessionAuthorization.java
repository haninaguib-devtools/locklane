package dev.locklane.engine.persistence;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one authorization check for whether a caller may view or attach to a
 * worktree/console session (#242, ADR-101 Decision 6) — replacing "first attach
 * claims it" (#48): a session's visibility now derives from its owning project's
 * {@code owner_user_id}, never from whichever authenticated request happened to
 * attach to it first (that is still recorded on the session row as
 * {@code owner_username}, but purely informational — see
 * {@link WorktreeSessionRepository#recordAttach}). A caller may see and attach only
 * to their own project's sessions — no role, administrator included, is exempt
 * (#394, ADR-105, withdrawing the administrator exemption in ADR-101 Decision 6).
 * Attaching is interactive code execution in the project's checkout, with that
 * project's decrypted GitHub token in the environment, so there is no weaker
 * "administrative read" of a session to grant.
 *
 * <p>Shared, deliberately, by every path that makes this decision — the REST
 * listings ({@link IssueWorktreeService}, {@link ProjectConsoleService}) and the
 * WebSocket attach itself ({@code TerminalWebSocketHandler}) — so the two can never
 * quietly drift into different answers for the same session id.
 */
@Service
public class WorktreeSessionAuthorization {

    // Every real worktree/console session id is shaped "<projectId>-..." (#43,
    // ProjectConsoleService's own "<projectId>-console..." family included) --
    // mirrors SessionRegistry's own PROJECT_ID_PREFIX, used there for the same
    // purpose (attributing a broadcast to its project).
    private static final Pattern PROJECT_ID_PREFIX = Pattern.compile("^(\\d+)-");

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public WorktreeSessionAuthorization(ProjectRepository projectRepository, UserRepository userRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    /** The project id encoded in a worktree/console session id's leading numeric segment, if any. */
    static Optional<Long> projectIdOf(String worktreeId) {
        Matcher matcher = PROJECT_ID_PREFIX.matcher(worktreeId);
        return matcher.find() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    /**
     * Whether {@code username} may view or attach to {@code worktreeId}. {@code
     * null} means "no caller to check against" — the handful of internal call
     * paths that intentionally bypass per-user filtering, exactly like {@link
     * IssueWorktreeService#hasAnySessions} and {@link IssueWorktreeService#allIssueWorktrees}
     * already do for system-level operations (no real HTTP or WebSocket request
     * ever reaches this with a null username: {@code SecurityConfig} requires
     * authentication on every path that leads here) — and is always visible.
     * Otherwise false (never an exception) for a username with no matching
     * account, a session id with no resolvable project, or a project that no
     * longer exists: there is no ownership left to check any of those against, so
     * the safer default is to show nothing rather than everything.
     */
    public boolean isVisibleTo(String worktreeId, String username) {
        if (username == null) {
            return true;
        }
        return userRepository.findByUsername(username)
                .map(caller -> isVisibleTo(worktreeId, caller))
                .orElse(false);
    }

    /** As {@link #isVisibleTo(String, String)}, when the caller's account is already resolved. */
    public boolean isVisibleTo(String worktreeId, UserRecord caller) {
        return projectIdOf(worktreeId)
                .flatMap(projectRepository::findById)
                .map(project -> project.ownerUserId() == caller.id())
                .orElse(false);
    }
}
