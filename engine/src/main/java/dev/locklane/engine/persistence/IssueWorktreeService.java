package dev.locklane.engine.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Associates persisted worktree sessions with the project and issue they belong to,
 * by convention: a worktree id shaped "&lt;projectId&gt;-&lt;issueNumber&gt;-&lt;slug&gt;"
 * belongs to that project's issue (#43) — matching the directory suffix
 * {@code WorktreeCreationService} creates and the {@code wip/<issueNumber>-<slug>}
 * branch name (AGENTS.md; the branch itself carries no project prefix, since each
 * project is its own independent repo with its own branch namespace) — with the
 * project id prepended because issue numbers can collide across projects while
 * worktree ids, the opaque WebSocket session key, cannot.
 *
 * <p>"main" and any id that does not start with two numeric segments belong to no
 * project/issue and never match — reported explicitly here rather than thrown, since
 * nothing enforces this naming today; a worktree id is just whatever string a
 * WebSocket client chose (#15). Project console ids — the legacy
 * {@code "<projectId>-console"} and the {@code "<projectId>-console-<suffix>"} family
 * minted since #177 (see {@link ProjectConsoleService}) — never match this pattern
 * either, since their second segment is the literal {@code console}, never a number;
 * {@link #allWorktreeIds} recognizes them separately (#194) so the header
 * indicator/picker can show project consoles alongside issue ones, but
 * {@link #worktreeIdsForIssue} and {@link #resumeSessionsForIssue} — both scoped to one
 * issue — correctly never match a console with no issue at all.
 *
 * <p>#585: the periodic cleanup sweep's own listing of per-issue worktrees
 * ({@link WorktreeCleanupSweeper#allIssueWorktrees()}) no longer sources from this
 * class's persisted records at all — closing an issue console deletes the very row
 * that discovery would need, so it asks git directly instead, the same way
 * {@link WorktreeCleanupSweeper#allProjectConsoleWorktrees()} already did for the
 * project-console family.
 */
@Service
public class IssueWorktreeService {

    private static final Pattern PROJECT_AND_ISSUE_PREFIXED = Pattern.compile("^(\\d+)-(\\d+)-");
    private static final Pattern PROJECT_CONSOLE_PREFIXED = Pattern.compile("^(\\d+)-console(-.+)?$");

    private final WorktreeSessionRepository repository;
    private final ConsoleResumeSessionRepository resumeRepository;
    private final WorktreeSessionAuthorization authorization;

    @Autowired
    public IssueWorktreeService(WorktreeSessionRepository repository,
            ConsoleResumeSessionRepository resumeRepository, WorktreeSessionAuthorization authorization) {
        this.repository = repository;
        this.resumeRepository = resumeRepository;
        this.authorization = authorization;
    }

    /** Test-only: resume-session listing off (#103) — callers that never touch it pass no resume repository. */
    public IssueWorktreeService(WorktreeSessionRepository repository, WorktreeSessionAuthorization authorization) {
        this(repository, null, authorization);
    }

    /**
     * Worktree ids known for this project's issue that {@code requestingUsername}
     * may see, empty if none — visibility is derived from the session's owning
     * project (#242, ADR-101 Decision 6, via {@link WorktreeSessionAuthorization}):
     * the project's owner sees every session in it; anyone else sees none of them,
     * regardless of who last attached and regardless of role (#394, ADR-105).
     */
    public List<String> worktreeIdsForIssue(long projectId, int issueNumber, String requestingUsername) {
        return repository.findAll().stream()
                .filter(record -> matches(record.worktreeId(), projectId, issueNumber))
                .filter(record -> isVisibleTo(record, requestingUsername))
                .map(WorktreeSessionRecord::worktreeId)
                .toList();
    }

    /**
     * Every worktree id {@code requestingUsername} may see, across every issue in
     * this project (#32's header indicator/picker, now scoped to one project since
     * #43), plus every open project-level console (#194) — same visibility rule as
     * {@link #worktreeIdsForIssue}, minus the single-issue filter. A bare {@code
     * "main"} or other id with no project/issue-number prefix and no project-console
     * shape is excluded: the picker has nowhere to navigate an id that belongs to
     * neither an issue nor the project's own console family.
     */
    public List<String> allWorktreeIds(long projectId, String requestingUsername) {
        return repository.findAll().stream()
                .filter(record -> matchesProject(record.worktreeId(), projectId)
                        || matchesProjectConsole(record.worktreeId(), projectId))
                .filter(record -> isVisibleTo(record, requestingUsername))
                .map(WorktreeSessionRecord::worktreeId)
                .toList();
    }

    /**
     * The Claude/Codex conversations captured (#102) in this project's issue's
     * consoles that {@code requestingUsername} may see, newest sighting first —
     * including conversations whose console has since been closed; outliving the
     * console is the point (#101). Visibility follows the console the id was
     * captured in, under the same project-owner rule as {@link #worktreeIdsForIssue}
     * (#242): a closed console has no session record any more, which is visible to
     * everyone since there is no project to resolve and check against.
     * The same conversation sighted in several consoles is listed once, at its
     * newest sighting.
     *
     * <p>A conversation captured in a legacy {@code "...-main-..."} console (#341
     * retired opening one) is excluded here rather than listed and then refused on
     * reopen: it can only ever be resumed in the project's main checkout it was
     * captured in — Claude/Codex key a stored conversation by directory, and that
     * checkout is no longer a console location — so there is nothing a reopen could
     * ever do with it, and listing it would just be a dead end in the Overview tab.
     */
    public List<ConsoleResumeSessionRecord> resumeSessionsForIssue(long projectId, int issueNumber,
            String requestingUsername) {
        Map<String, ConsoleResumeSessionRecord> byConversation = new LinkedHashMap<>();
        resumeRepository.findAll().stream()
                .filter(record -> matches(record.worktreeId(), projectId, issueNumber))
                .filter(record -> !isMainShaped(record.worktreeId()))
                .filter(record -> isConsoleVisibleTo(record.worktreeId(), requestingUsername))
                .sorted(Comparator.comparing(ConsoleResumeSessionRecord::capturedAt).reversed())
                .forEach(record -> byConversation.putIfAbsent(record.tool() + ":" + record.resumeId(), record));
        return List.copyOf(byConversation.values());
    }

    private static boolean isMainShaped(String worktreeId) {
        Matcher m = PROJECT_AND_ISSUE_PREFIXED.matcher(worktreeId);
        return m.find() && worktreeId.substring(m.end()).startsWith("main-");
    }

    private boolean isConsoleVisibleTo(String worktreeId, String requestingUsername) {
        return repository.find(worktreeId)
                .map(record -> isVisibleTo(record, requestingUsername))
                .orElse(true);
    }

    private static boolean matches(String worktreeId, long projectId, int issueNumber) {
        Matcher m = PROJECT_AND_ISSUE_PREFIXED.matcher(worktreeId);
        return m.find() && Long.parseLong(m.group(1)) == projectId && Integer.parseInt(m.group(2)) == issueNumber;
    }

    private static boolean matchesProject(String worktreeId, long projectId) {
        Matcher m = PROJECT_AND_ISSUE_PREFIXED.matcher(worktreeId);
        return m.find() && Long.parseLong(m.group(1)) == projectId;
    }

    private static boolean matchesProjectConsole(String worktreeId, long projectId) {
        Matcher m = PROJECT_CONSOLE_PREFIXED.matcher(worktreeId);
        return m.matches() && Long.parseLong(m.group(1)) == projectId;
    }

    private boolean isVisibleTo(WorktreeSessionRecord record, String requestingUsername) {
        return authorization.isVisibleTo(record.worktreeId(), requestingUsername);
    }

    /**
     * Whether this project has any open worktree or console session at all (#231's
     * delete refusal) — unlike {@link #allWorktreeIds}, ignores ownership entirely:
     * deleting the project would orphan a session no matter who owns it, so this is a
     * safety gate rather than a "what does this user see" listing.
     */
    public boolean hasAnySessions(long projectId) {
        return repository.findAll().stream()
                .anyMatch(record -> belongsToProject(record.worktreeId(), projectId));
    }

    /**
     * Forgets every worktree/console session belonging to this project (#240's
     * cascade-delete of a deleted user's owned projects, ADR-101 Decision 4) — the same
     * "does this session belong to this project" test as {@link #hasAnySessions}, but
     * removing the rows instead of just reporting them. Deliberately unconditional,
     * unlike the single-project delete path ({@code ProjectCheckoutService#delete}) that
     * refuses when {@link #hasAnySessions} is true: deleting the owning user is exactly
     * the case where these sessions are supposed to go away too, not block the delete.
     */
    public void deleteSessionsForProject(long projectId) {
        repository.findAll().stream()
                .filter(record -> belongsToProject(record.worktreeId(), projectId))
                .map(WorktreeSessionRecord::worktreeId)
                .forEach(repository::delete);
    }

    /**
     * Whether this session belongs to this project at all, whatever its family —
     * issue worktree, project console, or shell (#445) — the shared test behind
     * {@link #hasAnySessions} and {@link #deleteSessionsForProject}: both are
     * system-level sweeps over every session the project owns, so a family missing
     * here would let a project delete orphan a session, or a user cascade-delete
     * leave its rows behind.
     */
    private static boolean belongsToProject(String worktreeId, long projectId) {
        return matchesProject(worktreeId, projectId)
                || matchesProjectConsole(worktreeId, projectId)
                || ShellSessionService.belongsToProject(worktreeId, projectId);
    }

}
