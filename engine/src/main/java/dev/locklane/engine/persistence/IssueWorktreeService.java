package dev.locklane.engine.persistence;

import org.springframework.stereotype.Service;

import java.util.List;
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
 * WebSocket client chose (#15).
 */
@Service
public class IssueWorktreeService {

    private static final Pattern PROJECT_AND_ISSUE_PREFIXED = Pattern.compile("^(\\d+)-(\\d+)-");

    private final WorktreeSessionRepository repository;

    public IssueWorktreeService(WorktreeSessionRepository repository) {
        this.repository = repository;
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
     * #43) — same visibility rule as {@link #worktreeIdsForIssue}, minus the
     * single-issue filter. A bare {@code "main"} or other id with no
     * project/issue-number prefix is excluded: the picker has nowhere to navigate an
     * id that belongs to no issue.
     */
    public List<String> allWorktreeIds(long projectId, String requestingUsername) {
        return repository.findAll().stream()
                .filter(record -> matchesProject(record.worktreeId(), projectId))
                .filter(record -> isVisibleTo(record, requestingUsername))
                .map(WorktreeSessionRecord::worktreeId)
                .toList();
    }

    private static boolean matches(String worktreeId, long projectId, int issueNumber) {
        Matcher m = PROJECT_AND_ISSUE_PREFIXED.matcher(worktreeId);
        return m.find() && Long.parseLong(m.group(1)) == projectId && Integer.parseInt(m.group(2)) == issueNumber;
    }

    private static boolean matchesProject(String worktreeId, long projectId) {
        Matcher m = PROJECT_AND_ISSUE_PREFIXED.matcher(worktreeId);
        return m.find() && Long.parseLong(m.group(1)) == projectId;
    }

    private static boolean isVisibleTo(WorktreeSessionRecord record, String requestingUsername) {
        return record.ownerUsername() == null || record.ownerUsername().equals(requestingUsername);
    }
}
