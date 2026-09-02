package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhIssue;
import dev.locklane.engine.github.ProjectGhResources;
import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Periodically deletes a console-created worktree once it is safe to do so (#319) —
 * a deliberate, narrow exception to ADR-005's "left alone permanently, removed by
 * hand only": a worktree is removed automatically only when every one of these holds,
 * checked fresh at sweep time, and left untouched (never force-removed) otherwise:
 *
 * <ul>
 *   <li>its issue's cached state ({@link ProjectGhResources}/{@link
 *       dev.locklane.engine.github.GhIssueCache}) is {@code CLOSED} — a project or
 *       issue not found in the cache is treated the same as "not closed"</li>
 *   <li>{@code git status --porcelain} in the worktree is empty</li>
 *   <li>no live session ({@link SessionRegistry#hasLiveSessionIn}) has a working
 *       directory inside it</li>
 * </ul>
 *
 * <p>{@link #sweep()} is the whole of the guard logic, callable on its own schedule
 * below or, per this task's done-when, programmatically — #320's on-demand "run
 * cleanup now" trigger calls the same method rather than duplicating it.
 *
 * <p>{@link #removalRefusalReason} exposes the same three-part guard one worktree at a
 * time, with a human-readable reason attached to whichever check fails first — #320's
 * per-row manual "remove worktree" action calls this rather than re-deriving the
 * guard's conditions for itself, and shows the reason verbatim when it refuses.
 *
 * <p>#342 widens ADR-102's carve-out one step further: once a worktree is actually
 * removed, its local {@code wip/<id>-<slug>} branch would otherwise survive forever
 * (ADR-005) with nobody ever prompted to clean it up (the same "nobody is ever
 * prompted" reasoning ADR-102 applied to the worktree itself) — see ADR-103. {@link
 * #removeWorktree} attempts {@code git branch -d} (never {@code -D}, never retried)
 * on that branch immediately after the worktree is gone: git's own merge check is the
 * only judgment made, so a shipped branch goes and an unmerged one silently survives.
 *
 * <p>#339/ADR-104 adds a second, distinct carve-out alongside this one, for a
 * worktree-creation path this class's original guard was never written for: a
 * project console (no issue of its own, {@link ProjectConsoleService}). {@link
 * #allProjectConsoleWorktrees()}/{@link #removalRefusalReasonForProjectConsole}/
 * {@link #removeProjectConsoleWorktree} are that second guard's whole shape — session
 * ended, clean, and either HEAD is detached and an ancestor of {@code origin/main}
 * (a detached worktree has no branch to preserve a commit once its reflog is deleted
 * with it, unlike the per-issue case above), or a branch is checked out whose work
 * has already landed on {@code origin/main} — even under a rewritten SHA, e.g. via
 * squash-merge (#554/ADR-107, see {@link #isBranchLanded}) — while a checked-out
 * branch that still carries real, un-landed work refuses removal unconditionally,
 * unchanged from before ADR-107. Checked and removed the same all-or-nothing way, by
 * {@link #sweep()} as the periodic backstop and by {@link
 * ProjectConsoleService#close(long, String, String)} synchronously on tab close.
 * Discovery ({@link #allProjectConsoleWorktrees()}) is deliberately git-native
 * ({@code git worktree list --porcelain}, cross-referenced against the
 * sibling-directory naming convention {@link
 * ProjectConsoleService#startWorktreeSession} already uses), never from {@link
 * WorktreeSessionRepository}: a project console's tab-close already deletes its
 * persisted record unconditionally as part of ending the session, so a worktree this
 * guard refuses to remove would otherwise have no record left to find it by — and
 * asking git itself, rather than trusting directory names alone, means a same-named
 * but unrelated directory is never mistaken for a real project-console worktree.
 */
@Service
public class WorktreeCleanupSweeper {

    private static final Logger log = LoggerFactory.getLogger(WorktreeCleanupSweeper.class);
    private static final String CLOSED = "CLOSED";

    private final IssueWorktreeService issueWorktreeService;
    private final ProjectRepository projectRepository;
    private final ProjectGhResources ghResources;
    private final SessionRegistry sessionRegistry;
    private final GhAccountRepository ghAccountRepository;
    private final TokenCipher tokenCipher;

    @Autowired
    public WorktreeCleanupSweeper(IssueWorktreeService issueWorktreeService, ProjectRepository projectRepository,
            ProjectGhResources ghResources, SessionRegistry sessionRegistry, GhAccountRepository ghAccountRepository,
            TokenCipher tokenCipher) {
        this.issueWorktreeService = issueWorktreeService;
        this.projectRepository = projectRepository;
        this.ghResources = ghResources;
        this.sessionRegistry = sessionRegistry;
        this.ghAccountRepository = ghAccountRepository;
        this.tokenCipher = tokenCipher;
    }

    @Scheduled(fixedDelayString = "${locklane.worktree-cleanup.interval-ms}",
            initialDelayString = "${locklane.worktree-cleanup.interval-ms}")
    void scheduledSweep() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Scheduled worktree cleanup sweep failed", e);
        }
    }

    /**
     * Evaluates every console-created worktree once — per-issue and project-console
     * alike, each against its own guard — and removes each one the applicable guard
     * clears. Returns the worktree ids actually removed, so a caller (a test, or
     * #320's on-demand trigger) can report what happened rather than only that the
     * method ran.
     */
    public List<String> sweep() {
        List<String> removed = new ArrayList<>();
        for (IssueWorktreeService.ConsoleWorktree worktree : issueWorktreeService.allIssueWorktrees()) {
            if (isSafeToRemove(worktree) && removeWorktree(worktree)) {
                removed.add(worktree.worktreeId());
            }
        }
        for (ProjectConsoleWorktree worktree : allProjectConsoleWorktrees()) {
            if (removalRefusalReasonForProjectConsole(worktree).isEmpty() && removeProjectConsoleWorktree(worktree)) {
                removed.add(worktree.worktreeId());
            }
        }
        return removed;
    }

    private boolean isSafeToRemove(IssueWorktreeService.ConsoleWorktree worktree) {
        return removalRefusalReason(worktree).isEmpty();
    }

    /**
     * Empty when {@code worktree} clears every one of {@link #sweep()}'s three guard
     * conditions, checked fresh, in the same order {@link #sweep()} checks them;
     * otherwise the reason the first failing one refuses, worded for a human reading
     * it on the project page (#320) rather than a log line.
     */
    public Optional<String> removalRefusalReason(IssueWorktreeService.ConsoleWorktree worktree) {
        Optional<GhIssue> issue = ghResources.forProject(worktree.projectId())
                .flatMap(ctx -> ctx.cache().issue(worktree.issueNumber()));
        if (issue.isEmpty()) {
            return Optional.of("issue #" + worktree.issueNumber() + " could not be found — refusing to remove its worktree");
        }
        if (!CLOSED.equals(issue.get().state())) {
            return Optional.of("issue #" + worktree.issueNumber() + " is still open — close it before removing its worktree");
        }
        if (!isClean(worktree.workingDirectory())) {
            return Optional.of("the worktree has uncommitted changes — commit or discard them before removing it");
        }
        if (sessionRegistry.hasLiveSessionIn(worktree.workingDirectory())) {
            return Optional.of("a console session is still attached to this worktree — close it before removing the worktree");
        }
        return Optional.empty();
    }

    /** Whether {@code git status --porcelain} in {@code workingDirectory} reports nothing outstanding. */
    public boolean isClean(Path workingDirectory) {
        if (!Files.isDirectory(workingDirectory)) {
            // Already gone, or never actually created — nothing here to remove, and
            // "clean" would be a lie: there is no git status to have checked at all.
            return false;
        }
        Optional<String> output = run(workingDirectory, "git", "status", "--porcelain");
        return output.map(String::isEmpty).orElse(false);
    }

    /**
     * Removes exactly this worktree's directory (via {@code git worktree remove}, no
     * {@code --force}) and forgets its persisted session record. Callers outside
     * {@link #sweep()} — #320's manual remove action — must have already confirmed
     * {@link #removalRefusalReason} is empty; this method itself performs no guard
     * check, only the removal, so the guard is asked exactly once per call site
     * rather than silently re-run here too.
     */
    public boolean removeWorktree(IssueWorktreeService.ConsoleWorktree worktree) {
        Optional<ProjectRecord> project = projectRepository.findById(worktree.projectId());
        if (project.isEmpty()) {
            return false;
        }
        // Read the branch before the worktree disappears -- there is nothing left to
        // ask "what branch is this?" once the directory is gone. A detached HEAD (or
        // a failed read) means there is no branch to delete, not an error (#342: a
        // per-issue worktree may start detached once #340 lands).
        Optional<String> branch = currentBranch(worktree.workingDirectory());
        // No --force: git itself refuses on any uncommitted/untracked state, a second,
        // independent guard alongside the git-status check above in case of a race.
        Optional<String> output = run(project.get().workareaPath(), "git", "worktree", "remove",
                worktree.workingDirectory().toString());
        if (output.isEmpty()) {
            return false;
        }
        // The directory is gone: forget its persisted session record too (and, via
        // SessionRegistry#close, broadcast the same consolesChanged event a human
        // explicitly closing it would) so nothing lists a path that no longer exists.
        sessionRegistry.close(worktree.worktreeId());
        // ADR-103: the branch goes the same way the worktree just did, but only when
        // git itself considers it safe -- "-d", never "-D", and no retry on refusal.
        // Branches live in the shared repo, not per-worktree, so this runs against
        // the project's own checkout, which the worktree removal above never touches.
        branch.ifPresent(name -> run(project.get().workareaPath(), "git", "branch", "-d", name));
        return true;
    }

    /**
     * The branch currently checked out in {@code workingDirectory}, empty when HEAD is
     * detached ({@code git rev-parse --abbrev-ref HEAD} reports the literal {@code
     * HEAD}) or the read fails for any reason -- either way, "no branch to delete".
     */
    private Optional<String> currentBranch(Path workingDirectory) {
        return run(workingDirectory, "git", "rev-parse", "--abbrev-ref", "HEAD")
                .map(String::strip)
                .filter(name -> !name.isEmpty() && !"HEAD".equals(name));
    }

    /**
     * Every project-console worktree git itself considers registered to a project's
     * repository, across every project (#339) — {@code git worktree list --porcelain}
     * in each project's own checkout, cross-referenced against the sibling-directory
     * naming convention {@link ProjectConsoleService#startWorktreeSession} already
     * uses ({@code <repoName>-console-<suffix>}), never from {@link
     * WorktreeSessionRepository}: see this class's javadoc for why a DB-record-based
     * discovery would be wrong here. Asking git, rather than only matching directory
     * names on disk, is deliberate: a same-named but unrelated directory (a manual
     * backup, a stray clone parked next to the project) is never mistaken for a real
     * project-console worktree, because it was never actually registered as one of
     * this repository's linked worktrees. A project not found, or with no matching
     * registered worktree, simply contributes nothing.
     */
    public List<ProjectConsoleWorktree> allProjectConsoleWorktrees() {
        List<ProjectConsoleWorktree> result = new ArrayList<>();
        for (ProjectRecord project : projectRepository.findAll()) {
            Path projectRoot = project.workareaPath();
            if (!Files.isDirectory(projectRoot)) {
                continue;
            }
            String prefix = WorktreeCreationService.repoName(projectRoot) + "-console-";
            for (Path worktreePath : registeredWorktreePaths(projectRoot)) {
                Path fileName = worktreePath.getFileName();
                if (fileName == null || !fileName.toString().startsWith(prefix)) {
                    continue;
                }
                String suffix = fileName.toString().substring(prefix.length());
                String worktreeId = project.id() + "-console-" + suffix;
                result.add(new ProjectConsoleWorktree(project.id(), worktreeId, worktreePath));
            }
        }
        return result;
    }

    /**
     * Every worktree path {@code git worktree list --porcelain} reports for the
     * repository rooted at {@code projectRoot} — one {@code worktree <path>} line per
     * entry (blank-line separated; {@code HEAD}/{@code branch}/{@code detached}/etc.
     * lines are irrelevant here and skipped). Empty when the command itself fails —
     * treated the same as "nothing registered," never guessed from disk state.
     */
    private List<Path> registeredWorktreePaths(Path projectRoot) {
        Optional<String> output = run(projectRoot, "git", "worktree", "list", "--porcelain");
        if (output.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String line : output.get().split("\n")) {
            if (line.startsWith("worktree ")) {
                paths.add(Path.of(line.substring("worktree ".length()).strip()));
            }
        }
        return paths;
    }

    /**
     * Empty when {@code worktree} clears every one of this second guard's conditions
     * (#339/ADR-104, narrowed by #554/ADR-107), checked fresh, in the order stated
     * there; otherwise the reason the first failing one refuses, worded for a human
     * reading it on the project page rather than a log line — the same {@link
     * #removalRefusalReason} pattern the per-issue guard already established, applied
     * to a worktree-creation path that guard was never written for.
     */
    public Optional<String> removalRefusalReasonForProjectConsole(ProjectConsoleWorktree worktree) {
        if (sessionRegistry.hasLiveSessionIn(worktree.workingDirectory())) {
            return Optional.of("a console session is still attached to this worktree — close it before removing the worktree");
        }
        Optional<String> branch = currentBranch(worktree.workingDirectory());
        if (branch.isPresent() && !isBranchLanded(worktree.projectId(), worktree.workingDirectory())) {
            return Optional.of(
                    "a branch is checked out in this worktree, and its work has not landed on origin/main yet — it has outgrown scratch use, so it is left alone");
        }
        if (!isClean(worktree.workingDirectory())) {
            return Optional.of("the worktree has uncommitted changes — commit or discard them before removing it");
        }
        if (branch.isEmpty() && !isAncestorOfOriginMain(worktree.projectId(), worktree.workingDirectory())) {
            return Optional.of("this worktree has commits not yet reachable from origin/main — removing it would lose them");
        }
        return Optional.empty();
    }

    /**
     * Removes exactly this project-console worktree's directory (via {@code git
     * worktree remove}, no {@code --force}) and forgets its persisted session record,
     * if any is still left — the ordinary case is that it is already gone, tab-close
     * having deleted it as part of ending the session; {@link SessionRegistry#close}
     * is a documented no-op for an id with nothing live or recorded, so calling it
     * unconditionally here is safe. No branch-delete step (contrast {@link
     * #removeWorktree}): unlike the per-issue path, a project console's checked-out
     * branch is never this guard's own {@code wip/<id>-<slug>} branch to manage —
     * ADR-107 deliberately leaves a landed-but-still-checked-out branch to survive
     * ungoverned, the same as any other branch under ADR-005's default. Callers must
     * have already confirmed {@link #removalRefusalReasonForProjectConsole} is empty;
     * this method performs no guard check of its own.
     */
    public boolean removeProjectConsoleWorktree(ProjectConsoleWorktree worktree) {
        Optional<ProjectRecord> project = projectRepository.findById(worktree.projectId());
        if (project.isEmpty()) {
            return false;
        }
        Optional<String> output = run(project.get().workareaPath(), "git", "worktree", "remove",
                worktree.workingDirectory().toString());
        if (output.isEmpty()) {
            return false;
        }
        sessionRegistry.close(worktree.worktreeId());
        return true;
    }

    /**
     * Whether {@code workingDirectory}'s HEAD commit is reachable from a freshly
     * fetched {@code origin/main} — the one guard condition #339/ADR-104 needed that
     * ADR-102's per-issue guard never did: a detached worktree has no branch to
     * preserve a commit once the worktree (and its reflog) is removed, unlike a
     * per-issue worktree's named branch. Fetches first so a stale local
     * {@code origin/main} never says yes to a commit that has since been judged not
     * reachable; any failure along the way (no HEAD, fetch or merge-base failing) is
     * treated as "not an ancestor" — the safe direction, since it only ever keeps a
     * worktree around longer, never removes one it shouldn't. The fetch carries
     * {@code projectId}'s chosen account's token as {@code GH_TOKEN} (#551), so it
     * authenticates the same way every other git operation on this project's
     * checkout does — empty (no account chosen) leaves it to whatever ambient
     * credentials the host has, exactly as before #551.
     */
    private boolean isAncestorOfOriginMain(long projectId, Path workingDirectory) {
        run(workingDirectory, tokenEnvironment(projectId), "git", "fetch", "--prune", "origin");
        Optional<String> head = run(workingDirectory, Map.of(), "git", "rev-parse", "HEAD").map(String::strip);
        if (head.isEmpty()) {
            return false;
        }
        return run(workingDirectory, Map.of(), "git", "merge-base", "--is-ancestor", head.get(), "origin/main")
                .isPresent();
    }

    /**
     * Whether {@code workingDirectory}'s checked-out branch has already landed on a
     * freshly fetched {@code origin/main} (#554/ADR-107) — either its tip is a
     * literal ancestor (the ordinary fast-forward/merge-commit case, the same test
     * {@link #isAncestorOfOriginMain} uses for a detached worktree), or the whole
     * diff the branch introduces since its merge-base with {@code origin/main} is
     * content-equivalent (by {@code git patch-id --stable}) to some commit reachable
     * only through {@code origin/main} since that same base — the squash-merge or
     * rebase-merge case, where the SHA changes but the content does not. Any failure
     * along the way (fetch, merge-base, a patch-id computation) resolves to "not
     * landed" — the same safe direction {@link #isAncestorOfOriginMain} already
     * takes, since it only ever keeps a worktree around longer, never removes one it
     * shouldn't.
     */
    private boolean isBranchLanded(long projectId, Path workingDirectory) {
        run(workingDirectory, tokenEnvironment(projectId), "git", "fetch", "--prune", "origin");
        Optional<String> head = run(workingDirectory, Map.of(), "git", "rev-parse", "HEAD").map(String::strip);
        if (head.isEmpty()) {
            return false;
        }
        if (run(workingDirectory, Map.of(), "git", "merge-base", "--is-ancestor", head.get(), "origin/main")
                .isPresent()) {
            return true;
        }
        Optional<String> base = run(workingDirectory, "git", "merge-base", head.get(), "origin/main")
                .map(String::strip);
        if (base.isEmpty()) {
            return false;
        }
        Optional<String> branchPatchId = patchId(workingDirectory, "git", "diff", base.get(), head.get());
        if (branchPatchId.isEmpty()) {
            // Either the branch introduces no diff at all (e.g. an empty commit) or
            // the patch-id computation itself failed -- either way there is no
            // content to prove equivalent to anything on origin/main, so this stays
            // on the safe side: not landed. An empty commit still exists only on this
            // branch; it is not proof that whatever it marks has landed anywhere.
            return false;
        }
        Optional<String> candidates = run(workingDirectory, "git", "rev-list", base.get() + "..origin/main");
        if (candidates.isEmpty()) {
            return false;
        }
        for (String commit : candidates.get().split("\n")) {
            String candidate = commit.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            Optional<String> candidatePatchId = patchId(workingDirectory, "git", "show", candidate);
            if (candidatePatchId.isPresent() && candidatePatchId.get().equals(branchPatchId.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The stable patch-id ({@code git patch-id --stable}) of {@code
     * diffOrShowCommand}'s output — content-addresses a diff independently of the
     * commit metadata or SHA that produced it, which is exactly what tells a
     * squash-merged or rebase-merged commit apart from a genuinely different change.
     * Empty when the command produces no diff at all (nothing to compute a patch-id
     * from) or either process fails.
     */
    private Optional<String> patchId(Path workingDirectory, String... diffOrShowCommand) {
        return pipe(workingDirectory, diffOrShowCommand, new String[] {"git", "patch-id", "--stable"})
                .map(String::strip)
                .filter(output -> !output.isEmpty())
                .map(output -> output.split("\\s+")[0]);
    }

    /**
     * Runs {@code first} and feeds its stdout as {@code second}'s stdin, both in
     * {@code workingDirectory}, returning {@code second}'s stdout — {@code
     * ProcessBuilder.startPipeline} wires the two processes' streams together
     * directly, the same way a shell pipe would, without either command's output
     * passing through this JVM as an intermediate string. Empty when either process
     * exits non-zero.
     */
    private Optional<String> pipe(Path workingDirectory, String[] first, String[] second) {
        try {
            List<ProcessBuilder> builders = List.of(
                    new ProcessBuilder(first).directory(workingDirectory.toFile()),
                    new ProcessBuilder(second).directory(workingDirectory.toFile()).redirectErrorStream(true));
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            Process last = processes.get(processes.size() - 1);
            String output = new String(last.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean allSucceeded = true;
            for (Process process : processes) {
                if (process.waitFor() != 0) {
                    allSucceeded = false;
                }
            }
            if (!allSucceeded) {
                log.warn("'{} | {}' failed in {}", String.join(" ", first), String.join(" ", second), workingDirectory);
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            log.warn("Failed to run '{} | {}' in {}", String.join(" ", first), String.join(" ", second),
                    workingDirectory, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while running '{} | {}' in {}", String.join(" ", first), String.join(" ", second),
                    workingDirectory, e);
            return Optional.empty();
        }
    }

    /** {@code GH_TOKEN} for {@code projectId}'s chosen account, or empty when none is chosen (#551). */
    private Map<String, String> tokenEnvironment(long projectId) {
        return projectRepository.findGithubAccountId(projectId)
                .flatMap(ghAccountRepository::findEncryptedToken)
                .map(tokenCipher::decrypt)
                .map(token -> Map.of("GH_TOKEN", token))
                .orElse(Map.of());
    }

    /** One project-console worktree (#339) — no issue of its own, unlike {@link IssueWorktreeService.ConsoleWorktree}. */
    public record ProjectConsoleWorktree(long projectId, String worktreeId, Path workingDirectory) {
    }

    private Optional<String> run(Path workingDirectory, String... command) {
        return run(workingDirectory, Map.of(), command);
    }

    /** Same as {@link #run(Path, String...)}, with {@code env} added to the child's environment (#551). */
    private Optional<String> run(Path workingDirectory, Map<String, String> env, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true);
            builder.environment().putAll(env);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("'{}' failed ({}) in {}: {}", String.join(" ", command), exitCode, workingDirectory,
                        output.strip());
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException e) {
            log.warn("Failed to run '{}' in {}", String.join(" ", command), workingDirectory, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while running '{}' in {}", String.join(" ", command), workingDirectory, e);
            return Optional.empty();
        }
    }

}
