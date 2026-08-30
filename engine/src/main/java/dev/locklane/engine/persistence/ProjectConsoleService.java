package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.TokenCipher;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project-scoped console sessions (#139) — unlike a worktree session (#29), they have
 * no issue of their own. Since #314 each session gets its own freshly created git
 * worktree — a sibling checkout next to the project's own
 * ({@link ProjectCheckoutService}'s workarea), following the same
 * {@code ../<repo-name>-<suffix>} pattern. Since #338 that worktree's HEAD is
 * detached at current {@code origin/main} ({@link
 * WorktreeCreationService#createDetachedWorktree}) rather than sitting on a freshly
 * minted {@code console/<suffix>} branch: a console exists for pre-issue discussion
 * and almost never commits, so every one opened left a branch behind permanently. A
 * session that legitimately transitions to task work gets its proper
 * {@code wip/<id>-<slug>} branch from {@code /t-work} at that point instead — the
 * detached worktree still gives full file isolation in the meantime, rather than
 * every console sharing that one checkout, as before #314. Closing a console never
 * removes its worktree (that cleanup is deliberately deferred, per #314's task
 * record). A project can have several open at
 * once (#177): each {@link #start} mints a fresh id
 * {@code "<projectId>-console-<8-hex>"} — the same short-suffix convention
 * {@code WorktreeCreationService} uses for its {@code -main-}/{@code -resume-} ids —
 * stored in the same {@link WorktreeSessionRepository} table as any other session and
 * recognized purely by that id shape. The bare {@code "<projectId>-console"} id the
 * pre-#177 single-console code minted stays a member of the family, so a console
 * opened before this change keeps reattaching, resolving its environment, and closing
 * exactly as before. Neither shape ever collides with
 * {@link IssueWorktreeService}'s {@code ^(\d+)-(\d+)-} prefix (its second segment is
 * the literal {@code console}, not a number), so a project console never appears in
 * {@link IssueWorktreeService#worktreeIdsForIssue} or {@link
 * IssueWorktreeService#resumeSessionsForIssue} — both scoped to one issue, which a
 * project console has none of. It does appear in {@link
 * IssueWorktreeService#allWorktreeIds} (#194), the project-wide list the header
 * indicator/picker reads.
 */
@Service
public class ProjectConsoleService {

    // The whole family: the legacy bare "<projectId>-console" plus every
    // "<projectId>-console-<suffix>" minted since #177.
    private static final Pattern CONSOLE_SESSION_ID = Pattern.compile("^(\\d+)-console(-.+)?$");

    private final ProjectRepository projectRepository;
    private final TokenCipher tokenCipher;
    private final SessionRegistry sessionRegistry;
    private final WorktreeSessionRepository sessionRepository;
    private final WorktreeSessionAuthorization authorization;

    public ProjectConsoleService(ProjectRepository projectRepository, TokenCipher tokenCipher,
            SessionRegistry sessionRegistry, WorktreeSessionRepository sessionRepository,
            WorktreeSessionAuthorization authorization) {
        this.projectRepository = projectRepository;
        this.tokenCipher = tokenCipher;
        this.sessionRegistry = sessionRegistry;
        this.sessionRepository = sessionRepository;
        this.authorization = authorization;
    }

    /**
     * Mints a brand-new console session id in the project's family, creates it a
     * fresh sibling git worktree (#314), and reports that worktree's directory — a
     * fresh id and a fresh worktree every call (#177), so several consoles can run
     * side by side, each in its own checkout; never a reattach to one already open,
     * and never the project's shared checkout. Empty for an unknown or
     * not-yet-{@link ProjectStatus#READY} project — same rule
     * {@code WorktreeCreationService.startSession} already applies. No owner check
     * here: like starting a worktree session, the real ownership gate is the
     * WebSocket attach itself (see {@code TerminalWebSocketHandler}), not this
     * creation step.
     */
    public Optional<ConsoleSession> start(long projectId) {
        return projectRepository.findById(projectId)
                .filter(project -> project.status() == ProjectStatus.READY)
                .map(project -> startWorktreeSession(projectId, project.workareaPath()));
    }

    private ConsoleSession startWorktreeSession(long projectId, Path projectRoot) {
        String suffix = shortId();
        String sessionId = projectId + "-console-" + suffix;
        Path worktreePath = projectRoot.resolveSibling(WorktreeCreationService.repoName(projectRoot) + "-console-" + suffix);
        WorktreeCreationService.createDetachedWorktree(worktreePath, projectRoot);
        return new ConsoleSession(sessionId, worktreePath.toString());
    }

    /**
     * The project's current console session — the most recently attached open one
     * visible to {@code requestingUsername} (this project's owner, or an admin —
     * #242, same visibility rule as {@link IssueWorktreeService}). "Open" means it
     * has a persisted record: attached to at least once and not explicitly closed.
     * Empty when the project has none. With a single open console this is exactly
     * the pre-#177 "the project's one console" answer.
     */
    public Optional<ConsoleSession> find(long projectId, String requestingUsername) {
        return openRecords(projectId, requestingUsername).stream()
                .max(Comparator.comparing(WorktreeSessionRecord::lastAttachedAt))
                .map(record -> new ConsoleSession(record.worktreeId(), record.workingDirectory().toString()));
    }

    /**
     * Every open console session of the project's family that
     * {@code requestingUsername} may see, oldest-created first — a stable order for
     * a client tab strip (#178). Empty for a project with none (including an unknown
     * project — nothing recorded, nothing listed).
     */
    public List<OpenConsole> listOpen(long projectId, String requestingUsername) {
        return openRecords(projectId, requestingUsername).stream()
                .sorted(Comparator.comparing(WorktreeSessionRecord::createdAt))
                .map(record -> new OpenConsole(record.worktreeId(), record.workingDirectory().toString(),
                        record.createdAt(), record.lastAttachedAt()))
                .toList();
    }

    /**
     * Tears down the project's current console session — the one {@link #find}
     * reports — for good (#75-style close). False — nothing closed — for a project
     * with no open console visible to {@code requestingUsername}.
     */
    public boolean close(long projectId, String requestingUsername) {
        return find(projectId, requestingUsername)
                .map(session -> {
                    sessionRegistry.close(session.sessionId());
                    return true;
                })
                .orElse(false);
    }

    /**
     * Tears down one specific console session of the project's family (#177 — the
     * per-tab close). False — nothing closed — when {@code sessionId} is not in this
     * project's family, has no record, or is not visible to
     * {@code requestingUsername}.
     */
    public boolean close(long projectId, String sessionId, String requestingUsername) {
        boolean closeable = belongsToProject(sessionId, projectId)
                && sessionRepository.find(sessionId)
                        .map(record -> isVisibleTo(record, requestingUsername))
                        .orElse(false);
        if (!closeable) {
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

    private List<WorktreeSessionRecord> openRecords(long projectId, String requestingUsername) {
        return sessionRepository.findAll().stream()
                .filter(record -> belongsToProject(record.worktreeId(), projectId))
                .filter(record -> isVisibleTo(record, requestingUsername))
                .toList();
    }

    private static boolean belongsToProject(String sessionId, long projectId) {
        Matcher matcher = CONSOLE_SESSION_ID.matcher(sessionId);
        return matcher.matches() && Long.parseLong(matcher.group(1)) == projectId;
    }

    private boolean isVisibleTo(WorktreeSessionRecord record, String requestingUsername) {
        return authorization.isVisibleTo(record.worktreeId(), requestingUsername);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public record ConsoleSession(String sessionId, String workingDirectory) {
    }

    /** One row of {@link #listOpen} — what the consoles page (#179) renders. */
    public record OpenConsole(String sessionId, String workingDirectory, Instant createdAt, Instant lastAttachedAt) {
    }
}
