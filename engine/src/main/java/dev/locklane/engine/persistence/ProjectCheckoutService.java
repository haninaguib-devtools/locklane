package dev.locklane.engine.persistence;

import dev.locklane.engine.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Creates projects and clones them into their own workarea directory (#42), off the
 * request thread — {@code cloneExecutor} runs the actual {@code git clone} (a
 * virtual-thread executor in production; tests inject a same-thread one so the
 * outcome is asserted without polling). {@link #createNewProject} (#491) is the same
 * idea for a repository that doesn't exist yet: it creates one on GitHub via {@code gh}
 * first, then runs the same local-checkout-and-push shape.
 */
@Service
public class ProjectCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(ProjectCheckoutService.class);

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    /** #491's "bootstrap with t-workflow" checkbox — t-workflow's own one-command installer. */
    private static final String T_WORKFLOW_INSTALL_URL =
            "https://raw.githubusercontent.com/haninaguib-devtools/t-workflow/main/installer/install.sh";

    // A flag placed after a plain `| bash` is swallowed by bash itself rather than
    // forwarded to the script (install.sh --help) -- `bash -s --` is what actually
    // hands `--name` to the installer. The URL and project name ride as $1/$2
    // rather than being interpolated into the script text, so a name containing
    // shell metacharacters can't inject into the invocation.
    private static final String T_WORKFLOW_INSTALL_COMMAND = "curl -fsSL \"$1\" | bash -s -- --name \"$2\"";

    // The installer's bootstrap.sh refuses to make the project's first commit when git
    // has no committer identity, and nothing guarantees the host's git has a global
    // one -- the same reason the plain-init path sets a local identity before its
    // commit. git reads these over any config, so the bootstrap commit is always able
    // to happen, under the same identity the plain path uses.
    private static final Map<String, String> BOOTSTRAP_GIT_IDENTITY = Map.of(
            "GIT_AUTHOR_NAME", "locklane",
            "GIT_AUTHOR_EMAIL", "locklane@local",
            "GIT_COMMITTER_NAME", "locklane",
            "GIT_COMMITTER_EMAIL", "locklane@local");

    private final ProjectRepository repository;
    private final Path workareaRoot;
    private final Executor cloneExecutor;
    private final IssueWorktreeService issueWorktreeService;
    private final TokenCipher tokenCipher;
    private final Supplier<Optional<String>> ambientGithubToken;
    private final String installCommand;

    @Autowired
    public ProjectCheckoutService(ProjectRepository repository,
            @Value("${locklane.workarea-root}") String workareaRoot,
            @Qualifier("projectCloneExecutor") Executor cloneExecutor,
            IssueWorktreeService issueWorktreeService,
            TokenCipher tokenCipher) {
        this(repository, workareaRoot, cloneExecutor, issueWorktreeService, tokenCipher,
                ProjectCheckoutService::ghAuthToken);
    }

    /**
     * Test-only: lets a test substitute a fake "gh auth token" result instead of
     * shelling out to the real {@code gh} CLI (#513) — mirrors the existing contract
     * that {@link #setUpLocalRepoAndPush}'s own tests never invoke {@code gh}.
     */
    ProjectCheckoutService(ProjectRepository repository, String workareaRoot, Executor cloneExecutor,
            IssueWorktreeService issueWorktreeService, TokenCipher tokenCipher,
            Supplier<Optional<String>> ambientGithubToken) {
        this(repository, workareaRoot, cloneExecutor, issueWorktreeService, tokenCipher, ambientGithubToken,
                T_WORKFLOW_INSTALL_COMMAND);
    }

    /**
     * Test-only: additionally substitutes the t-workflow install command (#525), so a
     * test can exercise the whole bootstrap sequence against a local stub honouring
     * the real installer's contract instead of fetching the real one over the network.
     * The stub receives the same {@code $1} (installer URL) / {@code $2} (project
     * name) arguments the real command does.
     */
    ProjectCheckoutService(ProjectRepository repository, String workareaRoot, Executor cloneExecutor,
            IssueWorktreeService issueWorktreeService, TokenCipher tokenCipher,
            Supplier<Optional<String>> ambientGithubToken, String installCommand) {
        this.repository = repository;
        this.workareaRoot = Path.of(workareaRoot).normalize();
        this.cloneExecutor = cloneExecutor;
        this.issueWorktreeService = issueWorktreeService;
        this.tokenCipher = tokenCipher;
        this.ambientGithubToken = ambientGithubToken;
        this.installCommand = installCommand;
    }

    /**
     * Persists a new project in {@link ProjectStatus#CLONING} and starts cloning it
     * asynchronously. {@code requestedName} blank/{@code null} derives a name from
     * {@code gitUrl}; the workarea directory name is derived from the (derived or
     * given) name, disambiguated with a numeric suffix on collision. {@code ownerUserId}
     * (#239) is the authenticated caller creating the project — the workarea lands
     * under {@code workareas/<ownerUserId>/<slug>} (ADR-101 Decision 2), organizational
     * only, never itself the authorization boundary.
     */
    public ProjectRecord createProject(String gitUrl, String requestedName, long ownerUserId) {
        String trimmedUrl = gitUrl.strip();
        String name = (requestedName == null || requestedName.isBlank())
                ? deriveName(trimmedUrl) : requestedName.strip();
        Path workareaPath = uniqueWorkareaPath(ownerUserId, slug(name));

        ProjectRecord project = repository.create(name, trimmedUrl, workareaPath, ownerUserId, Instant.now());
        cloneExecutor.execute(() -> clone(project));
        return project;
    }

    /**
     * Persists a new project in {@link ProjectStatus#CLONING} and, asynchronously,
     * creates the GitHub repository at {@code org/name} via {@code gh} (private by
     * default), builds a local checkout for it in the project's workarea, and pushes
     * (#491) — {@code bootstrapTWorkflow} runs t-workflow's installer (which performs
     * its own {@code git init} and first commit) instead of a bare {@code git init}
     * plus a minimal {@code README.md}. {@code org} and {@code name} are both required:
     * unlike {@link #createProject}, there is no URL to derive a name from.
     */
    public ProjectRecord createNewProject(String org, String name, boolean bootstrapTWorkflow, long ownerUserId) {
        String trimmedOrg = org.strip();
        String trimmedName = name.strip();
        String gitUrl = "https://github.com/" + trimmedOrg + "/" + trimmedName + ".git";
        Path workareaPath = uniqueWorkareaPath(ownerUserId, slug(trimmedName));

        ProjectRecord project = repository.create(trimmedName, gitUrl, workareaPath, ownerUserId, Instant.now());
        cloneExecutor.execute(() -> createRepoAndPush(project, trimmedOrg, bootstrapTWorkflow));
        return project;
    }

    /** Re-clones a {@link ProjectStatus#FAILED} project from scratch. Empty if it doesn't exist or isn't failed. */
    public Optional<ProjectRecord> retry(long id) {
        Optional<ProjectRecord> existing = repository.findById(id);
        if (existing.isEmpty() || existing.get().status() != ProjectStatus.FAILED) {
            return Optional.empty();
        }
        deleteDirectoryQuietly(existing.get().workareaPath());
        repository.markCloning(id);
        ProjectRecord cloning = repository.findById(id).orElseThrow();
        cloneExecutor.execute(() -> clone(cloning));
        return Optional.of(cloning);
    }

    /**
     * Forgets the project and best-effort removes its workarea directory — refusing
     * (#231) when any worktree or console session is still open for it, so deleting
     * never orphans one out from under whoever is attached to it.
     */
    public DeleteOutcome delete(long id) {
        Optional<ProjectRecord> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return DeleteOutcome.NOT_FOUND;
        }
        if (issueWorktreeService.hasAnySessions(id)) {
            return DeleteOutcome.HAS_OPEN_SESSIONS;
        }
        repository.delete(id);
        deleteDirectoryQuietly(existing.get().workareaPath());
        return DeleteOutcome.DELETED;
    }

    public enum DeleteOutcome {
        NOT_FOUND, HAS_OPEN_SESSIONS, DELETED
    }

    /**
     * Unconditionally deletes a project and everything scoped to it: any worktree or
     * console sessions, its DB row, and its on-disk workarea checkout (best-effort, same
     * as {@link #delete}) — never refuses on an open session the way {@link #delete}
     * does. Only {@link UserCascadeDeleteService} calls this (#240, ADR-101 Decision 4):
     * cascade-deleting a user is exactly the case where its projects' sessions are
     * supposed to disappear along with the project, not block the delete the way they do
     * for an ordinary single-project delete. A no-op if the project is already gone, so
     * it is safe to call again after a partial failure.
     */
    public void forceDelete(long id) {
        Optional<ProjectRecord> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return;
        }
        issueWorktreeService.deleteSessionsForProject(id);
        repository.delete(id);
        deleteDirectoryQuietly(existing.get().workareaPath());
    }

    private void clone(ProjectRecord project) {
        try {
            Files.createDirectories(project.workareaPath().getParent());
            ProcessResult cloneResult = run("git", "clone", project.gitUrl(), project.workareaPath().toString());
            if (cloneResult.exitCode() != 0) {
                repository.markFailed(project.id());
                return;
            }
            ProcessResult branchResult =
                    run("git", "-C", project.workareaPath().toString(), "branch", "--show-current");
            String branch = branchResult.stdout().strip();
            if (branchResult.exitCode() != 0 || branch.isBlank()) {
                repository.markFailed(project.id());
                return;
            }
            repository.markReady(project.id(), branch);
        } catch (RuntimeException | IOException e) {
            repository.markFailed(project.id());
        }
    }

    private void createRepoAndPush(ProjectRecord project, String org, boolean bootstrapTWorkflow) {
        String repoSpec = org + "/" + project.name();
        try {
            ProcessResult createResult = run("gh", "repo", "create", repoSpec, "--private");
            if (createResult.exitCode() != 0) {
                log.warn("gh repo create {} failed for project {} (exit {}): {}", repoSpec, project.id(),
                        createResult.exitCode(), createResult.stderr().strip());
                repository.markFailed(project.id());
                return;
            }
            setUpLocalRepoAndPush(project, bootstrapTWorkflow);
        } catch (RuntimeException | IOException e) {
            log.warn("Failed to create new project {} ({})", project.id(), repoSpec, e);
            repository.markFailed(project.id());
        }
    }

    /**
     * Everything after the GitHub repository itself exists: a local checkout in
     * {@code project.workareaPath()} — either bootstrapped with t-workflow or a plain
     * {@code git init} plus a minimal README — pushed to {@code project.gitUrl()} as
     * {@code origin} (#491). Package-private so a test can exercise this whole local
     * sequence against a throwaway local bare repo standing in for the just-created
     * GitHub remote, without ever invoking {@code gh} itself.
     */
    void setUpLocalRepoAndPush(ProjectRecord project, boolean bootstrapTWorkflow) throws IOException {
        Path workarea = project.workareaPath();

        if (bootstrapTWorkflow) {
            // The installer never builds into its working directory: its contract is to
            // create the project at <cwd>/<name> (--dir defaults to the cwd), refusing
            // when that path already exists. So it runs in a scratch directory next to
            // the workarea (same filesystem, so the move below is a rename), and the
            // tree it produces is moved to the reserved workarea path -- whose slugged,
            // possibly suffix-disambiguated name can differ from the raw project name,
            // which is why the installer can't simply be pointed at the parent.
            Files.createDirectories(workarea.getParent());
            Path scratch = Files.createTempDirectory(workarea.getParent(), ".bootstrap-" + project.id() + "-");
            try {
                ProcessResult install = run(scratch, BOOTSTRAP_GIT_IDENTITY, "bash", "-c", installCommand,
                        "install-t-workflow", T_WORKFLOW_INSTALL_URL, project.name());
                if (install.exitCode() != 0) {
                    log.warn("t-workflow install failed for project {} (exit {}): {}", project.id(),
                            install.exitCode(), install.stderr().strip());
                    repository.markFailed(project.id());
                    return;
                }
                Path produced = scratch.resolve(project.name());
                if (!Files.isDirectory(produced.resolve(".git"))) {
                    log.warn("t-workflow install for project {} did not produce a git checkout at {}",
                            project.id(), produced);
                    repository.markFailed(project.id());
                    return;
                }
                Files.move(produced, workarea);
            } finally {
                deleteDirectoryQuietly(scratch);
            }
        } else {
            Files.createDirectories(workarea);
            Files.writeString(workarea.resolve("README.md"), "# " + project.name() + "\n");
            // Nothing guarantees the host's git has a global user.email/user.name
            // configured -- this is the first place the engine ever runs `git commit`
            // itself, so it sets its own local identity rather than assume one.
            if (run(workarea, "git", "init").exitCode() != 0
                    || run(workarea, "git", "config", "user.email", "locklane@local").exitCode() != 0
                    || run(workarea, "git", "config", "user.name", "locklane").exitCode() != 0
                    || run(workarea, "git", "add", "README.md").exitCode() != 0
                    || run(workarea, "git", "commit", "-m", "Initial commit").exitCode() != 0) {
                log.warn("Local git init/commit failed for project {}", project.id());
                repository.markFailed(project.id());
                return;
            }
        }

        ProcessResult branchResult = run(workarea, "git", "branch", "--show-current");
        String branch = branchResult.stdout().strip();
        if (branchResult.exitCode() != 0 || branch.isBlank()) {
            log.warn("Could not determine the default branch for project {}", project.id());
            repository.markFailed(project.id());
            return;
        }

        String remoteUrl = project.gitUrl();
        if (remoteUrl.startsWith("https://")) {
            Optional<String> token = resolveGithubToken(project);
            if (token.isEmpty()) {
                log.warn("No GitHub credentials available for project {} ({}) — no per-project token stored "
                                + "and `gh auth token` returned none; set a token for this project before retrying",
                        project.id(), remoteUrl);
                repository.markFailed(project.id());
                return;
            }
            remoteUrl = "https://x-access-token:" + token.get() + "@" + remoteUrl.substring("https://".length());
        }

        ProcessResult remoteAddResult = run(workarea, "git", "remote", "add", "origin", remoteUrl);
        if (remoteAddResult.exitCode() != 0) {
            log.warn("Push failed for project {} to {}: {}", project.id(), project.gitUrl(),
                    describe(remoteAddResult));
            repository.markFailed(project.id());
            return;
        }
        ProcessResult pushResult = run(workarea, "git", "push", "-u", "origin", branch);
        if (pushResult.exitCode() != 0) {
            log.warn("Push failed for project {} to {}: {}", project.id(), project.gitUrl(), describe(pushResult));
            repository.markFailed(project.id());
            return;
        }

        repository.markReady(project.id(), branch);
    }

    /**
     * The GitHub token to embed as HTTPS Basic-auth credentials in the push URL —
     * {@code x-access-token} is the conventional username GitHub accepts alongside a
     * PAT/installation token as the password — so the push authenticates on its own
     * rather than depending on whatever git/SSH credential setup happens to already
     * exist on the host (#505). Prefers the per-project token (#81) when one is
     * stored; a freshly created project has none yet, so it falls back to whatever
     * identity {@code gh} is already logged in as on this host (#513) — the same
     * identity {@link #createRepoAndPush} just rode on to create the repository.
     */
    private Optional<String> resolveGithubToken(ProjectRecord project) {
        Optional<String> stored = repository.findGithubToken(project.id()).map(tokenCipher::decrypt);
        return stored.isPresent() ? stored : ambientGithubToken.get();
    }

    /** The token behind {@code gh}'s own logged-in identity, or empty if there isn't one. */
    private static Optional<String> ghAuthToken() {
        try {
            ProcessResult result = run("gh", "auth", "token");
            if (result.exitCode() != 0) {
                return Optional.empty();
            }
            String token = result.stdout().strip();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        } catch (ProjectCheckoutException e) {
            return Optional.empty();
        }
    }

    /** Both streams of a failed command, for a log line that can actually explain why (#505). */
    private static String describe(ProcessResult result) {
        String out = result.stdout().strip();
        String err = result.stderr().strip();
        if (out.isEmpty()) {
            return err;
        }
        if (err.isEmpty()) {
            return out;
        }
        return out + " | " + err;
    }

    private Path uniqueWorkareaPath(long ownerUserId, String slug) {
        Path ownerRoot = workareaRoot.resolve(String.valueOf(ownerUserId));
        Path candidate = ownerRoot.resolve(slug);
        int suffix = 2;
        while (Files.exists(candidate) || repository.findByWorkareaPath(candidate).isPresent()) {
            candidate = ownerRoot.resolve(slug + "-" + suffix++);
        }
        return candidate;
    }

    private static void deleteDirectoryQuietly(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup — a leftover file here doesn't block anything.
                }
            });
        } catch (IOException ignored) {
            // Same: cleanup is best-effort.
        }
    }

    static String deriveName(String gitUrl) {
        String trimmed = gitUrl.replaceAll("/+$", "");
        int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        String tail = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        return tail.endsWith(".git") ? tail.substring(0, tail.length() - 4) : tail;
    }

    /** Same shape as {@code WorktreeCreationService.slug}, minus the length cap (directory names, not branches). */
    static String slug(String name) {
        String lower = name.toLowerCase();
        String dashed = NON_ALNUM.matcher(lower).replaceAll("-");
        String trimmed = dashed.replaceAll("^-+", "").replaceAll("-+$", "");
        return trimmed.isEmpty() ? "project" : trimmed;
    }

    private static ProcessResult run(String... command) {
        return run(null, command);
    }

    /** Same as {@link #run(String...)}, run in {@code cwd} instead of the engine's own working directory. */
    private static ProcessResult run(Path cwd, String... command) {
        return run(cwd, Map.of(), command);
    }

    /** Same as {@link #run(Path, String...)}, with {@code env} added to the child's environment. */
    private static ProcessResult run(Path cwd, Map<String, String> env, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            builder.environment().putAll(env);
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            return new ProcessResult(exit, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProjectCheckoutException("Interrupted while running git", e);
        } catch (IOException e) {
            throw new ProjectCheckoutException("Could not run git — is it installed and on PATH?", e);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    public static class ProjectCheckoutException extends RuntimeException {
        public ProjectCheckoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
