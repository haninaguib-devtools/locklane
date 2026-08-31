package dev.locklane.engine.persistence;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shell-kind console sessions (#445, part of #444): a plain shell — not an agent CLI
 * — at an issue's worktree or at the project's own main checkout, tracked in the same
 * {@link WorktreeSessionRepository} table as every other session and recognized
 * purely by id shape, exactly the way {@link ProjectConsoleService}'s console family
 * is. The shape is {@code "<projectId>-shell-<issueNumber>-<8-hex>"} for a shell at
 * an issue's worktree and {@code "<projectId>-shell-main-<8-hex>"} for one at the
 * project's main checkout: the literal {@code shell} second segment never matches
 * {@link IssueWorktreeService}'s {@code ^(\d+)-(\d+)-} (numeric second segment) or
 * {@link ProjectConsoleService}'s {@code ^(\d+)-console(-.+)?$}, so shells stay out
 * of every existing console-tab listing with no filtering added there, while the
 * leading {@code <projectId>-} gives them the same project-owner-derived visibility
 * ({@link WorktreeSessionAuthorization}) and WebSocket attach gate
 * ({@code TerminalWebSocketHandler}) every session already has.
 *
 * <p>Unlike an agent session — persisted only when a WebSocket first attaches —
 * {@link #open} persists the row at mint time: the returned id is immediately
 * attachable with {@code cmd=shell} and no {@code ?dir=}, because
 * {@code SessionRegistry.lastKnownWorkingDirectory} then resolves the directory from
 * the row. A shell never creates a worktree of its own: it runs in a directory some
 * other session family (or the project checkout itself) already owns, so nothing
 * here touches git and the worktree cleanup sweep never considers shells.
 *
 * <p>Several shells may target the same directory at once (one tailing logs, another
 * running a program) — every {@link #open} mints a fresh id, never reuses one.
 */
@Service
public class ShellSessionService {

    // The whole shell family. Group 1 is the project id; group 2 is the issue number,
    // or the literal "main" for a shell at the project's main checkout.
    private static final Pattern SHELL_SESSION_ID = Pattern.compile("^(\\d+)-shell-(main|\\d+)-[0-9a-f]{8}$");

    private final ProjectRepository projectRepository;
    private final WorktreeSessionRepository sessionRepository;
    private final WorktreeSessionAuthorization authorization;

    public ShellSessionService(ProjectRepository projectRepository, WorktreeSessionRepository sessionRepository,
            WorktreeSessionAuthorization authorization) {
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
        this.authorization = authorization;
    }

    /**
     * Mints a brand-new shell session at {@code workingDirectory} — an issue's
     * worktree when {@code issueNumber} is given, the project's main checkout when it
     * is {@code null} — persists it immediately, and reports the id to attach a
     * WebSocket to with {@code cmd=shell}. Empty for an unknown or
     * not-yet-{@link ProjectStatus#READY} project — the same rule every other
     * session-creating entry point applies. No owner check here: like starting any
     * other session, the real ownership gate is the WebSocket attach itself
     * ({@code TerminalWebSocketHandler}), not this creation step;
     * {@code creatingUsername} is stamped on the row as the informational
     * {@code owner_username} every attach records.
     */
    public Optional<ShellSession> open(long projectId, Integer issueNumber, Path workingDirectory,
            String creatingUsername) {
        return projectRepository.findById(projectId)
                .filter(project -> project.status() == ProjectStatus.READY)
                .map(project -> {
                    String sessionId = projectId + "-shell-" + (issueNumber == null ? "main" : issueNumber)
                            + "-" + shortId();
                    sessionRepository.recordAttach(sessionId, workingDirectory, Instant.now(), creatingUsername);
                    return new ShellSession(sessionId, workingDirectory.toString());
                });
    }

    /**
     * Every open shell session {@code requestingUsername} may see, across every
     * project — what the singleton Shells window's sidenav renders, grouped
     * client-side by {@link OpenShell#projectId} and, within a project, by
     * {@link OpenShell#issueNumber} or the main checkout. "Open" means the row still
     * exists: persisted at {@link #open} and not since closed. Oldest-created first —
     * the same stable order {@link ProjectConsoleService#listOpen} gives a tab strip.
     * Visibility is the project-owner rule every listing applies (#242, #394).
     */
    public List<OpenShell> listOpen(String requestingUsername) {
        return sessionRepository.findAll().stream()
                .filter(record -> isShellSession(record.worktreeId()))
                .filter(record -> authorization.isVisibleTo(record.worktreeId(), requestingUsername))
                .sorted(Comparator.comparing(WorktreeSessionRecord::createdAt)
                        .thenComparing(WorktreeSessionRecord::worktreeId))
                .map(ShellSessionService::toOpenShell)
                .toList();
    }

    /** Whether {@code sessionId} is in the shell family at all, any project. */
    public static boolean isShellSession(String sessionId) {
        return SHELL_SESSION_ID.matcher(sessionId).matches();
    }

    /**
     * Whether {@code sessionId} is one of this project's shells — what
     * {@link IssueWorktreeService#hasAnySessions} and
     * {@link IssueWorktreeService#deleteSessionsForProject} ask, so an open shell
     * blocks a project delete and a user cascade-delete removes shell rows, the same
     * as every other session family.
     */
    static boolean belongsToProject(String sessionId, long projectId) {
        Matcher matcher = SHELL_SESSION_ID.matcher(sessionId);
        return matcher.matches() && Long.parseLong(matcher.group(1)) == projectId;
    }

    private static OpenShell toOpenShell(WorktreeSessionRecord record) {
        Matcher matcher = SHELL_SESSION_ID.matcher(record.worktreeId());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a shell session id: " + record.worktreeId());
        }
        Integer issueNumber = matcher.group(2).equals("main") ? null : Integer.valueOf(matcher.group(2));
        return new OpenShell(record.worktreeId(), Long.parseLong(matcher.group(1)), issueNumber,
                issueNumber == null, record.workingDirectory().toString(), record.createdAt(),
                record.lastAttachedAt(), record.displayName());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** What {@link #open} hands back — the id to attach with {@code cmd=shell}, and where it runs. */
    public record ShellSession(String sessionId, String workingDirectory) {
    }

    /**
     * One row of {@link #listOpen}. {@code issueNumber} is {@code null} — and
     * {@code mainCheckout} true — for a shell at the project's main checkout;
     * {@code displayName} is the name the user gave this shell's row, {@code null}
     * when they gave it none, same as every other session's tab name (#393).
     */
    public record OpenShell(String sessionId, long projectId, Integer issueNumber, boolean mainCheckout,
            String workingDirectory, Instant createdAt, Instant lastAttachedAt, String displayName) {
    }
}
