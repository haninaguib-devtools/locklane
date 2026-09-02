package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.process.ProcessOutcome;
import dev.locklane.engine.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * current tip of the project's trunk on origin, leaving branch creation to
 * {@code /t-work} once implementation actually starts (see {@link #openIssueWorktree}).
 * A console opened only to discuss or plan an issue therefore leaves no branch behind.
 *
 * <p>Since #582 that trunk is the branch the project recorded when its checkout was
 * set up ({@link ProjectRecord#defaultBranch}, e.g. {@code master} for a repository
 * whose {@code git init} ran on a host with no {@code init.defaultBranch}), never a
 * hardcoded {@code main} — see {@link #trunkRef}. Every {@code git worktree add} here
 * used to name {@code origin/main} literally and failed outright, with
 * {@code fatal: invalid reference: origin/main}, on any project whose trunk is called
 * something else.
 *
 * <p>Since #43, the checkout a session is created against is resolved per project
 * (each project's own workarea, from {@link ProjectRepository}) rather than a
 * single fixed root — issue data itself stays global (a separate, deferred
 * concern; see #43's task record), but where a worktree lives on disk always
 * follows the project id in the request.
 */
@Service
public class WorktreeCreationService {

    private static final Logger log = LoggerFactory.getLogger(WorktreeCreationService.class);
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_SLUG_LENGTH = 40;

    /**
     * The trunk assumed for a project whose record carries no default branch (#582) —
     * a row still {@link ProjectStatus#CLONING}, or one that predates
     * {@code ProjectRepository#markReady} recording it. Exactly what every worktree
     * here was created from before #582, so an existing project behaves as it always
     * did.
     */
    static final String DEFAULT_TRUNK = "main";

    private final ProjectGhResources ghResources;
    private final IssueWorktreeService issueWorktreeService;
    private final ProjectRepository projectRepository;
    private final WorktreeSessionRepository sessionRepository;
    private final GhAccountRepository ghAccountRepository;
    private final TokenCipher tokenCipher;

    public WorktreeCreationService(ProjectGhResources ghResources, IssueWorktreeService issueWorktreeService,
            ProjectRepository projectRepository, WorktreeSessionRepository sessionRepository,
            GhAccountRepository ghAccountRepository, TokenCipher tokenCipher) {
        this.ghResources = ghResources;
        this.issueWorktreeService = issueWorktreeService;
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
        this.ghAccountRepository = ghAccountRepository;
        this.tokenCipher = tokenCipher;
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

        openIssueWorktree(issueNumber, worktreePath, projectRoot, trunkRef(project.get()), credentialFor(projectId));
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
        openIssueWorktree(issueNumber, worktreePath, projectRoot, trunkRef(project.get()), credentialFor(projectId));
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

    /** The project's token for the {@code git fetch} each worktree open starts with (#569) — plain git when it has none. */
    private GitCredential credentialFor(long projectId) {
        return GitCredential.forProject(projectId, projectRepository, ghAccountRepository, tokenCipher);
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
     * The remote-tracking ref every worktree of {@code project} is created from and
     * refreshed to (#582): {@code origin/<defaultBranch>} — the branch
     * {@code ProjectCheckoutService} detected and {@code ProjectRepository#markReady}
     * recorded, so a {@code master} project resolves to {@code origin/master} — or
     * {@code origin/}{@link #DEFAULT_TRUNK} when the record carries none. Package-visible
     * so {@link ProjectConsoleService} resolves it the same way.
     */
    static String trunkRef(ProjectRecord project) {
        return trunkRef(project.defaultBranch());
    }

    /** Same as {@link #trunkRef(ProjectRecord)}, from the recorded branch name itself — {@code null}/blank means {@link #DEFAULT_TRUNK}. */
    static String trunkRef(String defaultBranch) {
        String branch = defaultBranch == null || defaultBranch.isBlank() ? DEFAULT_TRUNK : defaultBranch.strip();
        return "origin/" + branch;
    }

    /**
     * Runs a real {@code git worktree add} for {@code branch} at {@code worktreePath}
     * inside {@code projectRoot} — package-visible (and static: it touches no
     * instance state) so {@link ProjectConsoleService} (#314) can reuse the exact
     * same git plumbing for a project console's own sibling worktree, rather than
     * duplicating it. A branch that does not exist yet is created from
     * {@code trunkRef} ({@link #trunkRef}, #582). {@code credential} authenticates the
     * initial {@code git fetch} as the project's account (#569).
     */
    static void createWorktree(String branch, Path worktreePath, Path projectRoot, String trunkRef,
            GitCredential credential) {
        fetch(projectRoot, credential);

        boolean branchExists =
                run("git", "-C", projectRoot.toString(), "rev-parse", "--verify", "--quiet", branch).exitCode() == 0
                || run("git", "-C", projectRoot.toString(), "rev-parse", "--verify", "--quiet", "origin/" + branch)
                        .exitCode() == 0;

        ProcessOutcome result = branchExists
                ? run("git", "-C", projectRoot.toString(), "worktree", "add", worktreePath.toString(), branch)
                : run("git", "-C", projectRoot.toString(), "worktree", "add", "-b", branch,
                        worktreePath.toString(), trunkRef);

        if (result.failed()) {
            log.warn("git worktree add failed for branch '{}' at {}: {}", branch, worktreePath, result.describe());
            throw new WorktreeCreationException(
                    "git worktree add failed for branch '" + branch + "': " + result.describe());
        }
    }

    /**
     * Opens the one worktree an issue console runs in, git-wise (#340): if a
     * {@code wip/<issueNumber>-*} branch already exists — locally or on origin, work is
     * already in flight there — check it out, so naming/branch-creation authority stays
     * with {@code /t-work} rather than being split between it and console-open.
     * Otherwise no branch is minted here at all: the worktree is created detached at
     * the current {@code trunkRef} — the project's trunk on origin, {@link #trunkRef}
     * (#582) — so a console opened only to discuss or plan leaves no machine-made
     * branch behind. When the worktree already exists on disk — a still-standing
     * checkout from an earlier console — and it is idle (detached, clean, its
     * {@code HEAD} entirely contained in {@code trunkRef}'s history, i.e. no commits
     * of its own), it is refreshed to the current {@code trunkRef} rather than handed
     * back as stale as the day it was created; a worktree carrying a branch, dirty
     * state, or its own commits is left untouched.
     */
    static void openIssueWorktree(int issueNumber, Path worktreePath, Path projectRoot, String trunkRef,
            GitCredential credential) {
        fetch(projectRoot, credential);

        if (Files.exists(worktreePath)) {
            refreshIfIdle(worktreePath, trunkRef);
            return;
        }

        Optional<String> branch = existingBranch(issueNumber, projectRoot);
        ProcessOutcome result = branch.isPresent()
                ? run("git", "-C", projectRoot.toString(), "worktree", "add", worktreePath.toString(), branch.get())
                : run("git", "-C", projectRoot.toString(), "worktree", "add", "--detach", worktreePath.toString(),
                        trunkRef);

        if (result.failed()) {
            log.warn("git worktree add failed for issue #{} at {}: {}", issueNumber, worktreePath,
                    result.describe());
            throw new WorktreeCreationException(
                    "git worktree add failed for issue #" + issueNumber + ": " + result.describe());
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
        ProcessOutcome result =
                run("git", "-C", projectRoot.toString(), "for-each-ref", "--format=%(refname:short)", pattern);
        return result.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst();
    }

    /**
     * Fast-forwards {@code worktreePath} to the current {@code trunkRef} when it is
     * idle — detached, clean, and carrying no commits of its own — leaving it alone
     * otherwise (#340). Best-effort: a failure here never stops the worktree from being
     * handed back, since the checkout itself is already usable as it stands.
     */
    private static void refreshIfIdle(Path worktreePath, String trunkRef) {
        boolean detached = run("git", "-C", worktreePath.toString(), "symbolic-ref", "-q", "HEAD").exitCode() != 0;
        if (!detached) {
            return;
        }
        boolean clean = run("git", "-C", worktreePath.toString(), "status", "--porcelain").stdout().isBlank();
        if (!clean) {
            return;
        }
        boolean noOwnCommits = run("git", "-C", worktreePath.toString(), "merge-base", "--is-ancestor", "HEAD",
                trunkRef).exitCode() == 0;
        if (!noOwnCommits) {
            return;
        }
        run("git", "-C", worktreePath.toString(), "checkout", "--detach", trunkRef);
    }

    /**
     * Runs a real {@code git worktree add --detach} for {@code worktreePath} inside
     * {@code projectRoot}, at current {@code trunkRef} — the project's trunk on origin,
     * {@link #trunkRef} (#582) — no branch is created or checked out (#338).
     * Package-visible (and static, like {@link #createWorktree}) so
     * {@link ProjectConsoleService} can reuse it for a project console's sibling
     * worktree: a console exists for pre-issue discussion and almost never commits,
     * so minting it a branch left one behind on disk permanently for every console
     * ever opened. The worktree still gives full file isolation between sessions; a
     * session that legitimately transitions to task work gets its proper
     * {@code wip/<id>-<slug>} branch from {@code /t-work} at that point instead.
     */
    static void createDetachedWorktree(Path worktreePath, Path projectRoot, String trunkRef,
            GitCredential credential) {
        fetch(projectRoot, credential);

        ProcessOutcome result = run("git", "-C", projectRoot.toString(), "worktree", "add", "--detach",
                worktreePath.toString(), trunkRef);

        if (result.failed()) {
            log.warn("git worktree add --detach failed at {}: {}", worktreePath, result.describe());
            throw new WorktreeCreationException(
                    "git worktree add --detach failed: " + result.describe());
        }
    }

    /**
     * {@link #createDetachedWorktree(Path, Path, String, GitCredential)} at
     * {@code origin/}{@link #DEFAULT_TRUNK}, for a caller with no project record in
     * hand — the sweeper and worktree-listing test fixtures, which build their repos
     * on {@code main}. Production callers always have the record and pass
     * {@link #trunkRef(ProjectRecord)} instead (#582).
     */
    static void createDetachedWorktree(Path worktreePath, Path projectRoot, GitCredential credential) {
        createDetachedWorktree(worktreePath, projectRoot, "origin/" + DEFAULT_TRUNK, credential);
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

    /**
     * The one remote-touching git call in this class (#569): {@code git fetch --prune
     * origin} in the project's main checkout, authenticated through {@code credential}
     * — the inline helper plus {@code GH_TOKEN} for an HTTPS remote with a stored
     * token, plain git otherwise. A failed fetch is logged and otherwise tolerated, as
     * it always was: the worktree add that follows still works from whatever
     * remote-tracking trunk ref the checkout already has.
     */
    private static void fetch(Path projectRoot, GitCredential credential) {
        ProcessOutcome result = run(credential.environment(),
                credential.command("-C", projectRoot.toString(), "fetch", "--prune", "origin"));
        if (result.failed()) {
            log.warn("git fetch --prune origin failed in {}: {}", projectRoot, result.describe());
        }
    }

    private static ProcessOutcome run(String... command) {
        return run(Map.of(), command);
    }

    /** Same as {@link #run(String...)}, with {@code env} added to the child's environment (#569). */
    private static ProcessOutcome run(Map<String, String> env, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(env);
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new ProcessOutcome(exit, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorktreeCreationException("Interrupted while running git", e);
        } catch (IOException e) {
            throw new WorktreeCreationException("Could not run git — is it installed and on PATH?", e);
        }
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
