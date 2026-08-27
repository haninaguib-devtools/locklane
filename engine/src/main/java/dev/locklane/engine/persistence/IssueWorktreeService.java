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
 */
@Service
public class IssueWorktreeService {

    private static final Pattern PROJECT_AND_ISSUE_PREFIXED = Pattern.compile("^(\\d+)-(\\d+)-");
    private static final Pattern PROJECT_CONSOLE_PREFIXED = Pattern.compile("^(\\d+)-console(-.+)?$");

    private final WorktreeSessionRepository repository;
    private final ConsoleResumeSessionRepository resumeRepository;

    @Autowired
    public IssueWorktreeService(WorktreeSessionRepository repository,
            ConsoleResumeSessionRepository resumeRepository) {
        this.repository = repository;
        this.resumeRepository = resumeRepository;
    }

    /** Test-only: resume-session listing off (#103) — callers that never touch it pass no resume repository. */
    public IssueWorktreeService(WorktreeSessionRepository repository) {
        this(repository, null);
    }

    /**
     * Worktree ids known for this project's issue that {@code requestingUsername}
     * may see, empty if none — a session owned by a different user is excluded
     * (#48); one with no recorded owner (created before per-user ownership existed,
     * or by an unauthenticated attach, no longer possible since #50 requires auth on
     * the WebSocket endpoint itself) is treated as unclaimed and included for
     * anyone.
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
     * captured in, under the same #48 rule as {@link #worktreeIdsForIssue}: a
     * closed console has no session record any more, which reads as unclaimed.
     * The same conversation sighted in several consoles is listed once, at its
     * newest sighting.
     */
    public List<ConsoleResumeSessionRecord> resumeSessionsForIssue(long projectId, int issueNumber,
            String requestingUsername) {
        Map<String, ConsoleResumeSessionRecord> byConversation = new LinkedHashMap<>();
        resumeRepository.findAll().stream()
                .filter(record -> matches(record.worktreeId(), projectId, issueNumber))
                .filter(record -> isConsoleVisibleTo(record.worktreeId(), requestingUsername))
                .sorted(Comparator.comparing(ConsoleResumeSessionRecord::capturedAt).reversed())
                .forEach(record -> byConversation.putIfAbsent(record.tool() + ":" + record.resumeId(), record));
        return List.copyOf(byConversation.values());
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

    private static boolean isVisibleTo(WorktreeSessionRecord record, String requestingUsername) {
        return record.ownerUsername() == null || record.ownerUsername().equals(requestingUsername);
    }
}
