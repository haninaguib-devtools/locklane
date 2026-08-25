package dev.locklane.engine.persistence;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Associates persisted worktree sessions with the issue they belong to, by
 * convention: a worktree id shaped "&lt;issueNumber&gt;-&lt;slug&gt;" belongs to
 * that issue — matching the directory suffix {@code /t-wtree} creates
 * ({@code ../<repo-name>-<id>}) and the {@code wip/<id>-<slug>} branch name
 * (AGENTS.md), with the repo-name/branch prefix stripped off since that is what a
 * WebSocket client actually supplies as the worktree id (#7).
 *
 * <p>"main" and any id that does not start with a number belong to no issue and
 * never match — reported explicitly here rather than thrown, since nothing enforces
 * this naming today; a worktree id is just whatever string a client chose (#15).
 */
@Service
public class IssueWorktreeService {

    private static final Pattern ISSUE_PREFIXED = Pattern.compile("^(\\d+)-");

    private final WorktreeSessionRepository repository;

    public IssueWorktreeService(WorktreeSessionRepository repository) {
        this.repository = repository;
    }

    /**
     * Worktree ids known for this issue that {@code requestingUsername} may see,
     * empty if none — a session owned by a different user is excluded (#48); one
     * with no recorded owner (created before per-user ownership existed, or by an
     * unauthenticated attach, still possible until #50) is treated as unclaimed and
     * included for anyone.
     */
    public List<String> worktreeIdsForIssue(int issueNumber, String requestingUsername) {
        return repository.findAll().stream()
                .filter(record -> matchesIssue(record.worktreeId(), issueNumber))
                .filter(record -> record.ownerUsername() == null || record.ownerUsername().equals(requestingUsername))
                .map(WorktreeSessionRecord::worktreeId)
                .toList();
    }

    private static boolean matchesIssue(String worktreeId, int issueNumber) {
        Matcher m = ISSUE_PREFIXED.matcher(worktreeId);
        return m.find() && Integer.parseInt(m.group(1)) == issueNumber;
    }
}
