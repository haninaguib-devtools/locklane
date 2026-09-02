package dev.locklane.engine.persistence;

import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.TokenCipher;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * every console sharing that one checkout, as before #314. Since #339/ADR-104,
 * closing a console tab attempts to remove its worktree too, guarded: the session
 * has just ended, HEAD is still detached (a checked-out branch means the console
 * outgrew scratch — left alone permanently, ADR-005), the worktree is clean, and its
 * HEAD is an ancestor of {@code origin/main} (so a commit made on detached HEAD is
 * never lost when the worktree's reflog goes with it). A worktree failing any of
 * those is kept — the same {@link WorktreeCleanupSweeper} guard is what
 * {@code WorktreeCleanupSweeper#sweep()} later re-checks as the backstop, and what
 * the project worktree list shows the refusal reason from. A project can have
 * several open at
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
 * indicator/picker reads, and since #372 the same conversations an issue's Overview
 * tab lists are listed for a project's own consoles here — see
 * {@link #resumeSessionsForProject} and {@link #reopenSession}.
 */
@Service
public class ProjectConsoleService {

    // The whole family: the legacy bare "<projectId>-console" plus every
    // "<projectId>-console-<suffix>" minted since #177.
    private static final Pattern CONSOLE_SESSION_ID = Pattern.compile("^(\\d+)-console(-.+)?$");
    // The tail {@link #reopenSession} appends to a console's own suffix (#372).
    // Stripped back off when the conversation's directory is derived from the id, so
    // reopening a reopened console still lands in the one directory the conversation
    // was ever captured in, instead of a chain of never-created ones.
    private static final Pattern REOPENED_SUFFIX = Pattern.compile("-resume-[0-9a-f]{8}$");

    private final ProjectRepository projectRepository;
    private final GhAccountRepository ghAccountRepository;
    private final TokenCipher tokenCipher;
    private final SessionRegistry sessionRegistry;
    private final WorktreeSessionRepository sessionRepository;
    private final ConsoleResumeSessionRepository resumeRepository;
    private final WorktreeSessionAuthorization authorization;
    private final WorktreeCleanupSweeper sweeper;

    public ProjectConsoleService(ProjectRepository projectRepository, GhAccountRepository ghAccountRepository,
            TokenCipher tokenCipher, SessionRegistry sessionRegistry, WorktreeSessionRepository sessionRepository,
            ConsoleResumeSessionRepository resumeRepository, WorktreeSessionAuthorization authorization,
            WorktreeCleanupSweeper sweeper) {
        this.projectRepository = projectRepository;
        this.ghAccountRepository = ghAccountRepository;
        this.tokenCipher = tokenCipher;
        this.sessionRegistry = sessionRegistry;
        this.sessionRepository = sessionRepository;
        this.resumeRepository = resumeRepository;
        this.authorization = authorization;
        this.sweeper = sweeper;
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
     * visible to {@code requestingUsername} (this project's owner, and nobody else
     * — #242, #394, same visibility rule as {@link IssueWorktreeService}). "Open" means it
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
                        record.createdAt(), record.lastAttachedAt(), record.displayName()))
                .toList();
    }

    /**
     * The past Claude/Codex/OpenCode conversations captured (#102) in this project's
     * own consoles that {@code requestingUsername} may see, newest sighting first
     * (#372) — the project-level counterpart of {@link
     * IssueWorktreeService#resumeSessionsForIssue}, reading the same
     * {@link ConsoleResumeSessionRepository} table, which {@code ResumeIdScanner}
     * already writes to for every console regardless of scope. Conversations whose
     * console has since been closed are the point: the row outlives the console
     * (#101). Visibility follows the console the conversation was captured in, under
     * the same project-owner rule as {@link #listOpen} (#242, #394) — decided
     * straight from the console id, which carries the project it belongs to, so a
     * closed console with no session record left is still filtered rather than shown
     * to everyone. The same conversation sighted in several of this project's
     * consoles is listed once, at its newest sighting.
     *
     * <p>The legacy bare {@code "<projectId>-console"} id is excluded — the
     * project-family counterpart of the {@code "...-main-..."} exclusion the
     * issue-scoped listing already makes. That id was only ever minted before #177,
     * and so only ever ran in the project's own shared checkout (#314 gave consoles
     * their own worktrees later); a conversation captured there can only be resumed
     * there, and #341 retired the project checkout as a console location — so listing
     * it would offer a reopen that could never work.
     */
    public List<ConsoleResumeSessionRecord> resumeSessionsForProject(long projectId, String requestingUsername) {
        Map<String, ConsoleResumeSessionRecord> byConversation = new LinkedHashMap<>();
        resumeRepository.findAll().stream()
                .filter(record -> belongsToProject(record.worktreeId(), projectId))
                .filter(record -> !consoleSuffix(record.worktreeId()).isEmpty())
                .filter(record -> authorization.isVisibleTo(record.worktreeId(), requestingUsername))
                .sorted(Comparator.comparing(ConsoleResumeSessionRecord::capturedAt).reversed())
                .forEach(record -> byConversation.putIfAbsent(record.tool() + ":" + record.resumeId(), record));
        return List.copyOf(byConversation.values());
    }

    /**
     * Mints a brand-new console session for reopening one of this project's past
     * conversations (#372), in the directory that conversation was captured in —
     * never a reattach, exactly like {@link WorktreeCreationService#reopenSession}
     * does for an issue: the original console may still be running, and the point is
     * a second console resuming the same conversation. The directory matters because
     * Claude/OpenCode key a stored conversation by working directory, so resuming
     * anywhere else finds nothing.
     *
     * <p>Where the issue-side reopen can always fall back to the issue's one stable
     * worktree path, a project console's directory belongs to that console alone —
     * and closing its tab deletes both its session record and (#339/ADR-104) its
     * worktree. So the directory is resolved from the record while one exists, and
     * otherwise rebuilt from the session id itself: {@code
     * "<projectId>-console-<suffix>"} always named the sibling checkout {@code
     * "<repoName>-console-<suffix>"} ({@link #startWorktreeSession}). A directory
     * that is gone is recreated as a fresh detached worktree at that same path, which
     * is all the CLI needs to find the conversation again.
     *
     * <p>The minted id carries the original console's suffix ahead of its own
     * {@code "-resume-<8-hex>"} tail, so it stays inside the project's console family
     * (reattaching, environment resolution and closing all work unchanged) while
     * still naming the directory it runs in. Empty when the project is not ready,
     * when {@code originalSessionId} is not one of this project's consoles, or when
     * it is the legacy bare {@code "<projectId>-console"} — refused for the same
     * reason {@link #resumeSessionsForProject} never lists it.
     */
    public Optional<ConsoleSession> reopenSession(long projectId, String originalSessionId) {
        Optional<ProjectRecord> project = projectRepository.findById(projectId);
        if (project.isEmpty() || project.get().status() != ProjectStatus.READY) {
            return Optional.empty();
        }
        if (!belongsToProject(originalSessionId, projectId)) {
            return Optional.empty();
        }
        Optional<Path> resolved = conversationDirectory(projectId, originalSessionId);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Path projectRoot = project.get().workareaPath();
        Path directory = resolved.get();
        if (!Files.exists(directory)) {
            WorktreeCreationService.createDetachedWorktree(directory, projectRoot);
        }
        return Optional.of(new ConsoleSession(
                projectId + "-console-" + originalConsoleSuffix(originalSessionId) + "-resume-" + shortId(),
                directory.toString()));
    }

    /**
     * Where a conversation captured in {@code sessionId} actually ran — its console's
     * recorded working directory while that record exists, and otherwise the sibling
     * checkout its id names ({@link #startWorktreeSession}), whether or not that
     * directory is still on disk. Empty for a session outside this project's console
     * family and for the legacy bare {@code "<projectId>-console"}, which ran in the
     * project's own shared checkout rather than a console worktree of its own.
     *
     * <p>Both {@link #reopenSession} and #373's title lookup need this same answer:
     * one to run a resumed conversation where the CLI will find it, the other to read
     * the title the CLI filed under that same directory.
     */
    public Optional<Path> conversationDirectory(long projectId, String sessionId) {
        Optional<ProjectRecord> project = projectRepository.findById(projectId);
        if (project.isEmpty() || !belongsToProject(sessionId, projectId)) {
            return Optional.empty();
        }
        String suffix = originalConsoleSuffix(sessionId);
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        Path projectRoot = project.get().workareaPath();
        return Optional.of(sessionRepository.find(sessionId)
                .map(WorktreeSessionRecord::workingDirectory)
                .orElseGet(() -> projectRoot.resolveSibling(
                        WorktreeCreationService.repoName(projectRoot) + "-console-" + suffix)));
    }

    /**
     * The file #536 commits a chosen template's body to, in the new repository's root
     * — what the seeded first prompt below tells the agent to read (#537).
     */
    static final String TEMPLATE_FILE = "PROJECT_TEMPLATE.md";

    /** Marks a checkout bootstrapped with t-workflow (#491): its own rules forbid changing the tree outside a task. */
    static final String T_WORKFLOW_MARKER = ".t-workflow";

    /**
     * The seeded first prompt for a plain (non-t-workflow) checkout (#537). A console
     * runs in a detached worktree, so a scaffold merely left there would never be
     * seen — the preface says to push it to {@code main} when done.
     */
    public static final String PLAIN_SEED_PROMPT =
            "This repository was just created from a project template. Read " + TEMPLATE_FILE
                    + " in the repository root and build the project it describes, working in the current"
                    + " worktree: implement it step by step, commit as you go, and push the result to main when"
                    + " it builds and its checks pass. Ask before anything destructive.";

    /**
     * The seeded first prompt for a t-workflow checkout (#537): that repository's
     * AGENTS.md forbids editing the tree outside a task, so the agent is told to open
     * one and drive it rather than build in place.
     */
    public static final String T_WORKFLOW_SEED_PROMPT =
            "This repository was just created from a project template and is governed by t-workflow: its"
                    + " AGENTS.md forbids changing the tree outside a task. Read " + TEMPLATE_FILE
                    + " in the repository root, then open a task with /t-open that asks for the scaffold it"
                    + " describes, and drive that task through the pipeline with /t-drive so the scaffold lands"
                    + " on main through a pull request. Ask before anything destructive.";

    /**
     * The engine-composed first prompt for a project console's seeded launch (#537),
     * or empty when this attach must not seed: {@code sessionId} is not a project
     * console's, its project is unknown, has no {@code template} (#536), or already
     * had its seeded console ({@code templateSeededAt} set). The preface is chosen from
     * the checkout itself — {@code workingDirectory} (the console's worktree, a
     * checkout of the project) carrying a {@link #T_WORKFLOW_MARKER} directory means a
     * t-workflow bootstrap — never from stored state. The template body is never part
     * of the prompt; the agent reads {@link #TEMPLATE_FILE} itself.
     */
    public Optional<String> templateSeedPrompt(String sessionId, Path workingDirectory) {
        return seedableProject(sessionId)
                .map(project -> Files.isDirectory(workingDirectory.resolve(T_WORKFLOW_MARKER))
                        ? T_WORKFLOW_SEED_PROMPT : PLAIN_SEED_PROMPT);
    }

    /**
     * Records that {@code sessionId}'s project just had its seeded console launched
     * (#537), at {@code now} — the write that turns {@link #templateSeedPrompt} off for
     * that project from here on. False, and nothing written, when the project was not
     * seedable in the first place (the same conditions as {@link #templateSeedPrompt}),
     * so a stray {@code seed} parameter can never stamp an unrelated project.
     */
    public boolean markTemplateSeeded(String sessionId, Instant now) {
        Optional<ProjectRecord> project = seedableProject(sessionId);
        if (project.isEmpty()) {
            return false;
        }
        projectRepository.markTemplateSeeded(project.get().id(), now);
        return true;
    }

    /** The project behind a console session id, if it still owes its seeded console (#537). */
    private Optional<ProjectRecord> seedableProject(String sessionId) {
        Matcher matcher = CONSOLE_SESSION_ID.matcher(sessionId);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return projectRepository.findById(Long.parseLong(matcher.group(1)))
                .filter(project -> project.template() != null && project.templateSeededAt() == null);
    }

    /** The longest tab name accepted (#393) — long enough to be useful, short enough not to break the strip. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 60;

    /**
     * Sets or clears the name a user gave one of this project's console tabs (#393).
     * A blank or {@code null} name clears it, so the client falls back to the label
     * it generates itself; anything else is stored trimmed. Whitespace-only input is
     * a clear rather than a name made of spaces.
     *
     * <p>{@link RenameOutcome#NOT_FOUND} — nothing renamed — when {@code sessionId}
     * is not in this project's console family, has no record, or is not visible to
     * {@code requestingUsername}: exactly the gate {@link #close(long, String,
     * String)} applies, so one user can no more rename another's console than close
     * it. {@link RenameOutcome#TOO_LONG} when the trimmed name exceeds
     * {@link #MAX_DISPLAY_NAME_LENGTH} — rejected rather than silently truncated, so
     * the user is told instead of surprised.
     */
    public RenameOutcome rename(long projectId, String sessionId, String requestingUsername, String name) {
        Optional<WorktreeSessionRecord> record = sessionRepository.find(sessionId);
        boolean renamable = belongsToProject(sessionId, projectId)
                && record.map(r -> isVisibleTo(r, requestingUsername)).orElse(false);
        if (!renamable) {
            return RenameOutcome.NOT_FOUND;
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            return RenameOutcome.TOO_LONG;
        }
        sessionRepository.setDisplayName(sessionId, trimmed.isEmpty() ? null : trimmed);
        return RenameOutcome.RENAMED;
    }

    /** What {@link #rename} did: renamed (or cleared), refused, or rejected as over-long. */
    public enum RenameOutcome {
        RENAMED,
        NOT_FOUND,
        TOO_LONG
    }

    /**
     * Tears down the project's current console session — the one {@link #find}
     * reports — for good (#75-style close), and, per {@link #close(long, String,
     * String)}, attempts to remove its worktree too. False — nothing closed — for a
     * project with no open console visible to {@code requestingUsername}.
     */
    public boolean close(long projectId, String requestingUsername) {
        return find(projectId, requestingUsername)
                .map(session -> close(projectId, session.sessionId(), requestingUsername))
                .orElse(false);
    }

    /**
     * Tears down one specific console session of the project's family (#177 — the
     * per-tab close) and, once the session has ended, attempts to remove its worktree
     * (#339/ADR-104): kept, never force-removed, when HEAD is not detached, the
     * worktree is dirty, or HEAD is not yet an ancestor of {@code origin/main} — the
     * same guard {@link WorktreeCleanupSweeper#removalRefusalReasonForProjectConsole}
     * exposes, so a refusal here and the periodic sweep's own backstop check never
     * quietly drift apart. False — nothing closed — when {@code sessionId} is not in
     * this project's family, has no record, or is not visible to
     * {@code requestingUsername}; the worktree-removal attempt is skipped entirely in
     * that case, same as before this task.
     */
    public boolean close(long projectId, String sessionId, String requestingUsername) {
        Optional<WorktreeSessionRecord> record = sessionRepository.find(sessionId);
        boolean closeable = belongsToProject(sessionId, projectId)
                && record.map(r -> isVisibleTo(r, requestingUsername)).orElse(false);
        if (!closeable) {
            return false;
        }
        // Captured before sessionRegistry#close deletes this record as part of ending
        // the session -- there is nothing left to read the working directory from
        // once that happens.
        Path workingDirectory = record.map(WorktreeSessionRecord::workingDirectory).orElse(null);
        sessionRegistry.close(sessionId);
        if (workingDirectory != null) {
            WorktreeCleanupSweeper.ProjectConsoleWorktree worktree =
                    new WorktreeCleanupSweeper.ProjectConsoleWorktree(projectId, sessionId, workingDirectory);
            if (sweeper.removalRefusalReasonForProjectConsole(worktree).isEmpty()) {
                sweeper.removeProjectConsoleWorktree(worktree);
            }
        }
        return true;
    }

    /**
     * The extra PTY environment for a session id, resolved purely from its own
     * shape — empty for anything that isn't a project console's id. A project with
     * no chosen GitHub account (#550) also resolves to empty: {@code gh} then falls
     * back to whatever ambient session the host has, exactly as it does for project
     * issue/PR fetches with no account chosen.
     */
    public Map<String, String> environmentFor(String sessionId) {
        Matcher matcher = CONSOLE_SESSION_ID.matcher(sessionId);
        if (!matcher.matches()) {
            return Map.of();
        }
        long projectId = Long.parseLong(matcher.group(1));
        return projectRepository.findGithubAccountId(projectId)
                .flatMap(ghAccountRepository::findEncryptedToken)
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

    /**
     * What follows {@code "<projectId>-console-"} in a console session id, or the
     * empty string for the legacy bare {@code "<projectId>-console"} (and for any id
     * outside the family). This is exactly the sibling-directory suffix
     * {@link #startWorktreeSession} named the console's worktree with.
     */
    private static String consoleSuffix(String sessionId) {
        Matcher matcher = CONSOLE_SESSION_ID.matcher(sessionId);
        if (!matcher.matches() || matcher.group(2) == null) {
            return "";
        }
        return matcher.group(2).substring(1);
    }

    /**
     * The suffix of the console a conversation was actually captured in, with any
     * {@code "-resume-<8-hex>"} tails {@link #reopenSession} appended stripped back
     * off — a reopened console runs in the original's directory, not one of its own,
     * so reopening from it must resolve to that same directory rather than to a path
     * nothing ever created.
     */
    private static String originalConsoleSuffix(String sessionId) {
        String suffix = consoleSuffix(sessionId);
        Matcher tail = REOPENED_SUFFIX.matcher(suffix);
        while (tail.find()) {
            suffix = suffix.substring(0, tail.start());
            tail = REOPENED_SUFFIX.matcher(suffix);
        }
        return suffix;
    }

    private boolean isVisibleTo(WorktreeSessionRecord record, String requestingUsername) {
        return authorization.isVisibleTo(record.worktreeId(), requestingUsername);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public record ConsoleSession(String sessionId, String workingDirectory) {
    }

    /**
     * One row of {@link #listOpen} — what the consoles page (#179) renders.
     * {@code displayName} is the name the user gave this tab (#393), or {@code null}
     * when they have given it none.
     */
    public record OpenConsole(String sessionId, String workingDirectory, Instant createdAt, Instant lastAttachedAt,
            String displayName) {
    }
}
