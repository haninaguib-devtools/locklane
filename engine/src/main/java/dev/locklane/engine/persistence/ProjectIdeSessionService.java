package dev.locklane.engine.persistence;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The project's main-checkout IDE session identity (#831): one canonical id per
 * project, {@code "<projectId>-ide-main"}, giving {@link AgentSessionsController#openIde}
 * — keyed on a session id it resolves a working directory for via
 * {@code SessionRegistry.lastKnownWorkingDirectory} — something to open at the
 * project's own main checkout. The same problem {@link ShellSessionService} already
 * solved for shells, minus the per-click fresh id: a shell explicitly supports several
 * open at once (#444), while a single IDE at the main checkout is meant to be reused
 * across opens, the same way {@code CodeServerService.start} already reuses a running
 * code-server process for a session id it has seen before — so opening this one twice
 * never starts a second code-server pointed at the same directory.
 *
 * <p>Deliberately excluded from {@link IssueWorktreeService#allWorktreeIds} (kept out of
 * the header indicator/picker and every agent-session-tab listing, the same as a shell)
 * and from the {@code hasAnySessions}/{@code deleteSessionsForProject} sweep {@link
 * IssueWorktreeService}'s own {@code belongsToProject} drives: unlike a shell, this
 * session has no explicit close and is meant to live for as long as the project itself
 * does, so counting it there would block a project delete forever the first time its
 * IDE was ever opened. Its row is simply left behind, harmless and never visible again
 * ({@link WorktreeSessionAuthorization#isVisibleTo} resolves to false once the project
 * is gone), on the rare path where the project is deleted anyway (2026-09-08).
 */
@Service
public class ProjectIdeSessionService {

    // The whole family: exactly one id per project, no per-click suffix — unlike
    // ShellSessionService's SHELL_SESSION_ID, which mints a fresh one every call.
    private static final Pattern MAIN_CHECKOUT_IDE_SESSION_ID = Pattern.compile("^(\\d+)-ide-main$");

    private final ProjectRepository projectRepository;
    private final WorktreeSessionRepository sessionRepository;
    private final WorktreeSessionAuthorization authorization;

    public ProjectIdeSessionService(ProjectRepository projectRepository, WorktreeSessionRepository sessionRepository,
            WorktreeSessionAuthorization authorization) {
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
        this.authorization = authorization;
    }

    /**
     * Ensures this project's main-checkout IDE session row exists, at the project's
     * current {@code workareaPath}, and reports its id and working directory —
     * idempotent, the same row reused (and its {@code lastAttachedAt} touched) on
     * every call, exactly like a repeated reattach to any other session. Empty for an
     * unknown or not-yet-{@link ProjectStatus#READY} project, or a caller who does not
     * own it — the same gate {@link ShellSessionService#open} applies before
     * persisting anything.
     */
    public Optional<ProjectIdeSession> open(long projectId, String requestingUsername) {
        return projectRepository.findById(projectId)
                .filter(project -> project.status() == ProjectStatus.READY)
                .filter(project -> authorization.isVisibleTo(projectId + "-ide-main", requestingUsername))
                .map(project -> {
                    String sessionId = projectId + "-ide-main";
                    Path workingDirectory = project.workareaPath();
                    sessionRepository.recordAttach(sessionId, workingDirectory, Instant.now(), requestingUsername);
                    return new ProjectIdeSession(sessionId, workingDirectory.toString());
                });
    }

    /**
     * Whether {@code sessionId} is this project's own main-checkout IDE session, has a
     * persisted row, and is visible to {@code requestingUsername} — the gate
     * {@link AgentSessionsController#openIde} applies alongside its existing
     * {@link IssueWorktreeService#allWorktreeIds} check, so opening this family's id
     * never requires it to be in that general listing.
     */
    public boolean isOpenAndVisibleTo(long projectId, String sessionId, String requestingUsername) {
        Matcher matcher = MAIN_CHECKOUT_IDE_SESSION_ID.matcher(sessionId);
        return matcher.matches() && Long.parseLong(matcher.group(1)) == projectId
                && sessionRepository.find(sessionId).isPresent()
                && authorization.isVisibleTo(sessionId, requestingUsername);
    }

    /** What {@link #open} hands back: the id to open an IDE at, and where it runs. */
    public record ProjectIdeSession(String sessionId, String workingDirectory) {
    }
}
