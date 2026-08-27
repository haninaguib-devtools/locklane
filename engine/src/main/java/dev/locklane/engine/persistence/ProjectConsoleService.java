package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.TokenCipher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project-scoped console session (#139) — unlike a worktree session (#29), it has
 * no issue and its working directory is always the project's own checkout
 * ({@link ProjectCheckoutService}'s workarea), never a `git worktree add` copy. One
 * such session exists per project: its id is minted deterministically as
 * {@code "<projectId>-console"} and stored in the same {@link WorktreeSessionRepository}
 * table as any other session, recognized purely by that id shape — the same
 * convention {@link IssueWorktreeService} already uses to exclude a bare
 * {@code "main"} id from its project/issue-prefixed matching. This shape never
 * collides with that class's {@code ^(\d+)-(\d+)-} prefix (one numeric group, not
 * two), so a project console never appears in the per-issue/console-picker lists
 * those methods serve — consistent with this task's "no client UI yet" boundary.
 */
@Service
public class ProjectConsoleService {

    private static final Pattern CONSOLE_SESSION_ID = Pattern.compile("^(\\d+)-console$");

    private final ProjectRepository projectRepository;
    private final TokenCipher tokenCipher;
    private final SessionRegistry sessionRegistry;

    public ProjectConsoleService(ProjectRepository projectRepository, TokenCipher tokenCipher,
            SessionRegistry sessionRegistry) {
        this.projectRepository = projectRepository;
        this.tokenCipher = tokenCipher;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Mints (or simply reports) the project's one console session id and its
     * working directory. Empty for an unknown or not-yet-{@link ProjectStatus#READY}
     * project — same rule {@code WorktreeCreationService.startSession} already
     * applies. No owner check here: like starting a worktree session, the real
     * ownership gate is the WebSocket attach itself (see
     * {@code TerminalWebSocketHandler}), not this creation step.
     */
    public Optional<ConsoleSession> start(long projectId) {
        return projectRepository.findById(projectId)
                .filter(project -> project.status() == ProjectStatus.READY)
                .map(project -> new ConsoleSession(sessionId(projectId), project.workareaPath().toString()));
    }

    /**
     * The project's console session if one has actually been attached to before
     * (i.e. it has a persisted record) and is visible to {@code requestingUsername}
     * — unclaimed or owned by them, same visibility rule as
     * {@link IssueWorktreeService}. Empty otherwise, including for a project that
     * has never had a console session started.
     */
    public Optional<ConsoleSession> find(long projectId, String requestingUsername) {
        String sessionId = sessionId(projectId);
        return sessionRegistry.lastKnownWorkingDirectory(sessionId)
                .filter(dir -> isVisibleTo(sessionId, requestingUsername))
                .map(dir -> new ConsoleSession(sessionId, dir.toString()));
    }

    /**
     * Tears down the project's console session for good (#75-style close). False —
     * nothing closed — for a project with no recorded console session, or one that
     * exists but is not visible to {@code requestingUsername}.
     */
    public boolean close(long projectId, String requestingUsername) {
        String sessionId = sessionId(projectId);
        if (sessionRegistry.lastKnownWorkingDirectory(sessionId).isEmpty()
                || !isVisibleTo(sessionId, requestingUsername)) {
            return false;
        }
        sessionRegistry.close(sessionId);
        return true;
    }

    /**
     * The extra PTY environment for a session id, resolved purely from its own
     * shape — empty for anything that isn't a project console's id. A project
     * console with no stored GitHub token also resolves to empty: {@code gh} then
     * falls back to whatever ambient session the host has, exactly as it does for
     * project issue/PR fetches with no token configured (#81).
     */
    public Map<String, String> environmentFor(String sessionId) {
        Matcher matcher = CONSOLE_SESSION_ID.matcher(sessionId);
        if (!matcher.matches()) {
            return Map.of();
        }
        long projectId = Long.parseLong(matcher.group(1));
        return projectRepository.findGithubToken(projectId)
                .map(tokenCipher::decrypt)
                .map(token -> Map.of("GH_TOKEN", token))
                .orElse(Map.of());
    }

    private boolean isVisibleTo(String sessionId, String requestingUsername) {
        return sessionRegistry.ownerUsername(sessionId)
                .map(owner -> owner.equals(requestingUsername))
                .orElse(true);
    }

    private static String sessionId(long projectId) {
        return projectId + "-console";
    }

    public record ConsoleSession(String sessionId, String workingDirectory) {
    }
}
