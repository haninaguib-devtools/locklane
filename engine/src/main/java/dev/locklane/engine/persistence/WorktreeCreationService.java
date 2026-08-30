package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.ProjectGhResources;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Starts a new agent session for an issue that has none yet, so the client (#20)
 * never has to guess a working directory itself. Mirrors what {@code /t-wtree}
 * already does for the human-facing pipeline — a real {@code git worktree add}, in a
 * sibling {@code ../<repo-name>-<id>} checkout — chosen over a lighter-weight non-git
 * convention because per-worktree isolation is the whole point of this app's agent
 * model (ADR-002); a session that silently reused the main checkout would undermine
 * that. See the task record for the full reasoning.
 *
 * <p>Since #340, opening a console never mints a {@code wip/<id>-<slug>} branch itself:
 * if one already exists for the issue (locally or on origin) it is checked out — work
 * is already in flight there — otherwise the worktree is created detached at the
 * current {@code origin/main}, leaving branch creation to {@code /t-work} once
 * implementation actually starts (see {@link #openIssueWorktree}). A console opened
 * only to discuss or plan an issue therefore leaves no branch behind.
 *
 * <p>Since #43, the checkout a session is created against is resolved per project
 * (each project's own workarea, from {@link ProjectRepository}) rather than a
 * single fixed root — issue data itself stays global (a separate, deferred
 * concern; see #43's task record), but where a worktree lives on disk always
 * follows the project id in the request.
 */
@Service
public class WorktreeCreationService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_SLUG_LENGTH = 40;

    private final ProjectGhResources ghResources;
    private final IssueWorktreeService issueWorktreeService;
    private final ProjectRepository projectRepository;
    private final WorktreeSessionRepository sessionRepository;

    public WorktreeCreationService(ProjectGhResources ghResources, IssueWorktreeService issueWorktreeService,
            ProjectRepository projectRepository, WorktreeSessionRepository sessionRepository) {
        this.ghResources = ghResources;
        this.issueWorktreeService = issueWorktreeService;
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Starts (or, if one already exists, simply reports) the one worktree session
     * for a project's issue, including its working directory — a brand new worktree
     * has no persisted session yet (nothing has attached to it via WebSocket to
     * record one, #6/#15), so the client cannot resolve that directory on its own
     * the way it can for an already-known worktree; it must be told directly, to
     * pass as {@code ?dir=} on the first attach (#7). Empty if the project or the
     * issue itself is not known.
     *
     * <p>Equivalent to {@code startSession(projectId, issueNumber, null)} — kept for
     * existing callers that pass no requesting user.
     */
    public Optional<StartedSession> startSession(long projectId, int issueNumber) {
        return startSession(projectId, issueNumber, null);
    }

    /**
     * As above, but only an existing session {@code requestingUsername} may see
     * (their own, or one with no recorded owner, #48) is reused — a session another
     * user owns is invisible here, same as it is in {@link IssueWorktreeService}.
     *
     * <p>Every session this method starts runs in a real {@code git worktree add}
     * checkout, never the project's own main checkout (#341 retired that option: it
     * let several sessions share the one checkout every {@code git worktree}
     * operation and the cleanup sweep themselves run from — exactly what the
     * workflow forbids, and in the one place it could break worst). A project
     * console (#314) covers the "a console with no worktree yet" use case this
     * used to serve, with its own cheap detached scratch worktree instead.
     */
    public Optional<StartedSession> startSession(long projectId, int issueNumber, String requestingUsername) {
        Optional<ProjectRecord> project = projectRepository.findById(projectId);
        if (project.isEmpty() || project.get().status() != ProjectStatus.READY) {
            return Optional.empty();
        }
        Path projectRoot = project.get().workareaPath();

        Path worktreePath = projectRoot.resolveSibling(repoName(projectRoot) + "-" + issueNumber);

        // Excludes any surviving legacy main-checkout session id (shaped
        // "<projectId>-<issueNumber>-main-<suffix>" — #341 retired minting new ones,
        // but an old persisted record can still exist) and reopened-conversation ids
        // ("...-resume-<suffix>", minted by reopenSession): they match the
        // project/issue prefix but are not the issue's one reusable worktree session.
        String mainSessionPrefix = projectId + "-" + issueNumber + "-main-";
        String resumeSessionPrefix = projectId + "-" + issueNumber + "-resume-";
        List<String> existing = issueWorktreeService.worktreeIdsForIssue(projectId, issueNumber, requestingUsername)
                .stream()
                .filter(id -> !id.startsWith(mainSessionPrefix) && !id.startsWith(resumeSessionPrefix))
                .toList();
        if (!existing.isEmpty()) {
            return Optional.of(new StartedSession(existing.get(0), worktreePath.toString()));
        }

        Optional<GhIssue> issue = issue(projectId, issueNumber);
        if (issue.isEmpty()) {
            return Optional.empty();
        }

        String slug = slug(issue.get().title());
        String worktreeId = projectId + "-" + issueNumber + "-" + slug;

        openIssueWorktree(issueNumber, worktreePath, projectRoot);
        return Optional.of(new StartedSession(worktreeId, worktreePath.toString()));
    }

    /**
     * Mints a brand-new session for reopening a past Claude/Codex conversation
     * (#103) next to the console it was captured in — never a reattach: the
     * original console may still be running, and the point is a second console
     * resuming the same conversation. The new session runs in the original
     * console's working directory, because Claude keys stored conversations by
     * directory: the original session's recorded directory when that record still
     * exists, otherwise the issue's worktree path (recreated if it is gone). The
     * minted id keeps the "-resume-" shape so it lists under the issue like any
     * other console without ever being mistaken for the issue's one reusable
     * worktree session. Empty when the project is not ready, {@code
     * originalWorktreeId} does not belong to this project's issue, or {@code
     * originalWorktreeId} is a legacy {@code "...-main-..."} console (#341
     * retired opening a console against the project's main checkout, and a
     * conversation captured there can only ever be resumed there — there is no
     * worktree that would actually contain it, so this is refused deliberately
     * rather than silently resumed in the wrong directory; the controller's
     * {@code /resume-sessions} listing excludes these for the same reason, so this
     * only guards a caller reaching the id some other way).
     */
    public Optional<StartedSession> reopenSession(long projectId, int issueNumber, String originalWorktreeId) {
        Optional<ProjectRecord> project = projectRepository.findById(projectId);
        if (project.isEmpty() || project.get().status() != ProjectStatus.READY) {
            return Optional.empty();
        }
        if (!originalWorktreeId.startsWith(projectId + "-" + issueNumber + "-")) {
            return Optional.empty();
        }
        if (originalWorktreeId.startsWith(projectId + "-" + issueNumber + "-main-")) {
            return Optional.empty();
        }
        Path projectRoot = project.get().workareaPath();
        String sessionId = projectId + "-" + issueNumber + "-resume-" + shortId();

        Optional<Path> recordedDirectory = sessionRepository.find(originalWorktreeId)
                .map(WorktreeSessionRecord::workingDirectory);
        if (recordedDirectory.isPresent()) {
            return Optional.of(new StartedSession(sessionId, recordedDirectory.get().toString()));
        }
        Path worktreePath = projectRoot.resolveSibling(repoName(projectRoot) + "-" + issueNumber);
        if (!Files.exists(worktreePath) && issue(projectId, issueNumber).isEmpty()) {
            return Optional.empty();
        }
        openIssueWorktree(issueNumber, worktreePath, projectRoot);
        return Optional.of(new StartedSession(sessionId, worktreePath.toString()));
    }

    /**
     * Where a conversation captured in {@code worktreeId} actually ran — the console's
     * recorded working directory while that record exists, and otherwise the issue's
     * one sibling checkout, whether or not it is still on disk. The project-console
     * counterpart is {@link ProjectConsoleService#conversationDirectory}.
     *
     * <p>#373 needs this to read a conversation's generated title: Claude and OpenCode
     * both file a stored conversation under the directory it ran in, so the title
     * cannot be found without knowing that directory. Empty for a project that is not
     * ready and for a {@code worktreeId} outside this project's issue.
     */
    public Optional<Path> conversationDirectory(long projectId, int issueNumber, String worktreeId) {
        Optional<ProjectRecord> project = projectRepository.findById(projectId);
        if (project.isEmpty() || project.get().status() != ProjectStatus.READY) {
            return Optional.empty();
        }
        if (!worktreeId.startsWith(projectId + "-" + issueNumber + "-")) {
            return Optional.empty();
        }
        Path projectRoot = project.get().workareaPath();
        return Optional.of(sessionRepository.find(worktreeId)
                .map(WorktreeSessionRecord::workingDirectory)
                .orElseGet(() -> projectRoot.resolveSibling(repoName(projectRoot) + "-" + issueNumber)));
    }

    private Optional<GhIssue> issue(long projectId, int issueNumber) {
        return ghResources.forProject(projectId).flatMap(ctx -> ctx.cache().issue(issueNumber));
    }

    private static String shortId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public record StartedSession(String worktreeId, String workingDirectory) {
    }

    /**
     * Runs a real {@code git worktree add} for {@code branch} at {@code worktreePath}
     * inside {@code projectRoot} — package-visible (and static: it touches no
     * instance state) so {@link ProjectConsoleService} (#314) can reuse the exact
     * same git plumbing for a project console's own sibling worktree, rather than
     * duplicating it.
     */
    static void createWorktree(String branch, Path worktreePath, Path projectRoot) {
        run("git", "-C", projectRoot.toString(), "fetch", "--prune", "origin");

        boolean branchExists =
                run("git", "-C", projectRoot.toString(), "rev-parse", "--verify", "--quiet", branch).exitCode() == 0
                || run("git", "-C", projectRoot.toString(), "rev-parse", "--verify", "--quiet", "origin/" + branch)
                        .exitCode() == 0;

        ProcessResult result = branchExists
                ? run("git", "-C", projectRoot.toString(), "worktree", "add", worktreePath.toString(), branch)
                : run("git", "-C", projectRoot.toString(), "worktree", "add", "-b", branch,
                        worktreePath.toString(), "origin/main");

        if (result.exitCode() != 0) {
            throw new WorktreeCreationException(
                    "git worktree add failed for branch '" + branch + "': " + result.stderr().strip());
        }
    }

    /**
     * Opens the one worktree an issue console runs in, git-wise (#340): if a
     * {@code wip/<issueNumber>-*} branch already exists — locally or on origin, work is
     * already in flight there — check it out, so naming/branch-creation authority stays
     * with {@code /t-work} rather than being split between it and console-open.
     * Otherwise no branch is minted here at all: the worktree is created detached at
     * the current {@code origin/main}, so a console opened only to discuss or plan
     * leaves no machine-made branch behind. When the worktree already exists on disk —
     * a still-standing checkout from an earlier console — and it is idle (detached,
     * clean, its {@code HEAD} entirely contained in {@code origin/main}'s history, i.e.
     * no commits of its own), it is refreshed to the current {@code origin/main} rather
     * than handed back as stale as the day it was created; a worktree carrying a
     * branch, dirty state, or its own commits is left untouched.
     */
    static void openIssueWorktree(int issueNumber, Path worktreePath, Path projectRoot) {
        run("git", "-C", projectRoot.toString(), "fetch", "--prune", "origin");

        if (Files.exists(worktreePath)) {
            refreshIfIdle(worktreePath);
            return;
        }

        Optional<String> branch = existingBranch(issueNumber, projectRoot);
        ProcessResult result = branch.isPresent()
                ? run("git", "-C", projectRoot.toString(), "worktree", "add", worktreePath.toString(), branch.get())
                : run("git", "-C", projectRoot.toString(), "worktree", "add", "--detach", worktreePath.toString(),
                        "origin/main");

        if (result.exitCode() != 0) {
            throw new WorktreeCreationException(
                    "git worktree add failed for issue #" + issueNumber + ": " + result.stderr().strip());
        }
    }

    /**
     * The name of an already-existing {@code wip/<issueNumber>-*} branch — checked
     * locally first, then among origin's remote-tracking refs (with the {@code origin/}
     * prefix stripped, since {@code git worktree add} takes a branch name, not a
     * remote-tracking ref) — or empty when neither has one. Assumes the caller already
     * fetched, so origin's remote-tracking refs are current.
     */
    private static Optional<String> existingBranch(int issueNumber, Path projectRoot) {
        String prefix = "wip/" + issueNumber + "-";
        Optional<String> local = firstMatchingRef(projectRoot, "refs/heads/" + prefix + "*");
        if (local.isPresent()) {
            return local;
        }
        return firstMatchingRef(projectRoot, "refs/remotes/origin/" + prefix + "*")
                .map(ref -> ref.substring("origin/".length()));
    }

    private static Optional<String> firstMatchingRef(Path projectRoot, String pattern) {
        ProcessResult result =
                run("git", "-C", projectRoot.toString(), "for-each-ref", "--format=%(refname:short)", pattern);
        return result.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst();
    }

    /**
     * Fast-forwards {@code worktreePath} to the current {@code origin/main} when it is
     * idle — detached, clean, and carrying no commits of its own — leaving it alone
     * otherwise (#340). Best-effort: a failure here never stops the worktree from being
     * handed back, since the checkout itself is already usable as it stands.
     */
    private static void refreshIfIdle(Path worktreePath) {
        boolean detached = run("git", "-C", worktreePath.toString(), "symbolic-ref", "-q", "HEAD").exitCode() != 0;
        if (!detached) {
            return;
        }
        boolean clean = run("git", "-C", worktreePath.toString(), "status", "--porcelain").stdout().isBlank();
        if (!clean) {
            return;
        }
        boolean noOwnCommits = run("git", "-C", worktreePath.toString(), "merge-base", "--is-ancestor", "HEAD",
                "origin/main").exitCode() == 0;
        if (!noOwnCommits) {
            return;
        }
        run("git", "-C", worktreePath.toString(), "checkout", "--detach", "origin/main");
    }

    /**
     * Runs a real {@code git worktree add --detach} for {@code worktreePath} inside
     * {@code projectRoot}, at current {@code origin/main} — no branch is created or
     * checked out (#338). Package-visible (and static, like {@link #createWorktree})
     * so {@link ProjectConsoleService} can reuse it for a project console's sibling
     * worktree: a console exists for pre-issue discussion and almost never commits,
     * so minting it a branch left one behind on disk permanently for every console
     * ever opened. The worktree still gives full file isolation between sessions; a
     * session that legitimately transitions to task work gets its proper
     * {@code wip/<id>-<slug>} branch from {@code /t-work} at that point instead.
     */
    static void createDetachedWorktree(Path worktreePath, Path projectRoot) {
        run("git", "-C", projectRoot.toString(), "fetch", "--prune", "origin");

        ProcessResult result = run("git", "-C", projectRoot.toString(), "worktree", "add", "--detach",
                worktreePath.toString(), "origin/main");

        if (result.exitCode() != 0) {
            throw new WorktreeCreationException(
                    "git worktree add --detach failed: " + result.stderr().strip());
        }
    }

    /** Package-visible for the same reason as {@link #createWorktree}: shared with {@link ProjectConsoleService}. */
    static String repoName(Path projectRoot) {
        Path name = projectRoot.getFileName();
        return name != null ? name.toString() : "repo";
    }

    /** Same shape as the wip/&lt;id&gt;-&lt;slug&gt; branch convention (AGENTS.md). */
    static String slug(String title) {
        String lower = title.toLowerCase();
        String dashed = NON_ALNUM.matcher(lower).replaceAll("-");
        String trimmed = dashed.replaceAll("^-+", "").replaceAll("-+$", "");
        String truncated = trimmed.length() > MAX_SLUG_LENGTH ? trimmed.substring(0, MAX_SLUG_LENGTH) : trimmed;
        return truncated.replaceAll("-+$", "");
    }

    private static ProcessResult run(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new ProcessResult(exit, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorktreeCreationException("Interrupted while running git", e);
        } catch (IOException e) {
            throw new WorktreeCreationException("Could not run git — is it installed and on PATH?", e);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    public static class WorktreeCreationException extends RuntimeException {
        public WorktreeCreationException(String message) {
            super(message);
        }

        public WorktreeCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
