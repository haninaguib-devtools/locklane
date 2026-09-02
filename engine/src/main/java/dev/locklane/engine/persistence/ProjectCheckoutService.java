package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhAccount;
import dev.locklane.engine.process.ProcessOutcome;
import dev.locklane.engine.security.TokenCipher;
import dev.locklane.engine.template.ProjectTemplate;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Creates projects and clones them into their own workarea directory (#42), off the
 * request thread — {@code cloneExecutor} runs the actual {@code git clone} (a
 * virtual-thread executor in production; tests inject a same-thread one so the
 * outcome is asserted without polling). {@link #createNewProject} (#491) is the same
 * idea for a repository that doesn't exist yet: it creates one on GitHub via {@code gh}
 * first, then runs the same local-checkout-and-push shape.
 *
 * <p>Either path may name a {@code githubAccountId} (#550): one of the GitHub
 * accounts the caller has signed in to Locklane ({@code GhAccountRepository}). When
 * it does, that account's token is stored (encrypted) as the project's own before
 * anything else happens, so the project's issue/PR fetches act as that identity from
 * the first fetch; on the create path {@code gh repo create} and the first push
 * additionally run with {@code GH_TOKEN} set to it. With no account chosen, no token
 * is available anywhere in this class — there is no ambient/host fallback (removed by
 * #550, which replaced the #532/#513 host-{@code gh}-login model entirely): a push
 * that needs credentials and finds none fails with a clear message instead.
 *
 * <p>The create path may also name a {@link ProjectTemplate} (#536). Its name is stored
 * on the project row and its body is committed as {@link #TEMPLATE_FILE} in the
 * checkout root before the first push — inside the initial commit on the plain
 * {@code git init} path, as one extra commit on top of the t-workflow installer's tree
 * on the bootstrap path. Nothing here reads or runs the template; #537 hands it to an
 * agent in a console later. With no template, both paths are byte-for-byte unchanged.
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

    /**
     * The classic OAuth scope GitHub demands of any token that creates or updates a
     * file under {@code .github/workflows/} -- which a t-workflow bootstrap's first push
     * always does ({@code ci.yml}). A token without it is rejected at the push (#531).
     */
    static final String WORKFLOW_SCOPE = "workflow";

    /** The operator's fix when the chosen account's token lacks {@link #WORKFLOW_SCOPE}; quoted verbatim in the log. */
    static final String GRANT_WORKFLOW_SCOPE_COMMAND = "gh auth refresh -h github.com -s workflow";

    /**
     * A git credential helper that always answers from the {@code GH_TOKEN}
     * environment variable of whatever process consults it, rather than anything
     * cached or typed interactively (#551) — configured repo-locally (never global)
     * in a project's main checkout right after it exists, so {@code git remote -v}
     * shows a plain HTTPS URL and no token is ever embedded in it or written to disk
     * anywhere. Every worktree shares this checkout's config, so a push from inside
     * one authenticates the same way as long as {@code GH_TOKEN} is in its own
     * process environment.
     */
    static final String CREDENTIAL_HELPER_SCRIPT = "!f() { echo username=x-access-token; echo password=$GH_TOKEN; }; f";

    /** Where a chosen template's body lands in a new repository (#536) — the file the seeded agent reads (#537). */
    static final String TEMPLATE_FILE = "PROJECT_TEMPLATE.md";

    /** The subject of the extra commit carrying {@link #TEMPLATE_FILE} on the bootstrap path (#536). */
    static final String TEMPLATE_COMMIT_SUBJECT = "Add project template";

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
    private final GhAccountRepository ghAccountRepository;
    private final String installCommand;
    private final String ghExecutable;

    @Autowired
    public ProjectCheckoutService(ProjectRepository repository,
            @Value("${locklane.workarea-root}") String workareaRoot,
            @Qualifier("projectCloneExecutor") Executor cloneExecutor,
            IssueWorktreeService issueWorktreeService,
            TokenCipher tokenCipher, GhAccountRepository ghAccountRepository) {
        this(repository, workareaRoot, cloneExecutor, issueWorktreeService, tokenCipher, ghAccountRepository,
                T_WORKFLOW_INSTALL_COMMAND, "gh");
    }

    /**
     * Test-only: lets a test substitute the t-workflow install command (#525), so a
     * test can exercise the whole bootstrap sequence against a local stub honouring
     * the real installer's contract instead of fetching the real one over the network.
     * The stub receives the same {@code $1} (installer URL) / {@code $2} (project
     * name) arguments the real command does.
     */
    ProjectCheckoutService(ProjectRepository repository, String workareaRoot, Executor cloneExecutor,
            IssueWorktreeService issueWorktreeService, TokenCipher tokenCipher, GhAccountRepository ghAccountRepository,
            String installCommand) {
        this(repository, workareaRoot, cloneExecutor, issueWorktreeService, tokenCipher, ghAccountRepository,
                installCommand, "gh");
    }

    /**
     * Test-only: additionally substitutes the {@code gh} executable (#532) — a path to
     * a stub script standing in for the real CLI — so {@code gh repo create} can be
     * exercised without ever invoking the real {@code gh}, the same way
     * {@code installCommand} stands in for the real installer.
     */
    ProjectCheckoutService(ProjectRepository repository, String workareaRoot, Executor cloneExecutor,
            IssueWorktreeService issueWorktreeService, TokenCipher tokenCipher, GhAccountRepository ghAccountRepository,
            String installCommand, String ghExecutable) {
        this.repository = repository;
        this.workareaRoot = Path.of(workareaRoot).normalize();
        this.cloneExecutor = cloneExecutor;
        this.issueWorktreeService = issueWorktreeService;
        this.tokenCipher = tokenCipher;
        this.ghAccountRepository = ghAccountRepository;
        this.installCommand = installCommand;
        this.ghExecutable = ghExecutable;
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
        return createProject(gitUrl, requestedName, ownerUserId, null);
    }

    /**
     * Same as {@link #createProject(String, String, long)}, acting as
     * {@code githubAccountId} (#550): that account's token is stored as the project's
     * own before the clone, which itself is unchanged (the URL, SSH alias and key
     * decide the clone's identity). {@code null} means no account was chosen.
     */
    public ProjectRecord createProject(String gitUrl, String requestedName, long ownerUserId, Long githubAccountId) {
        String trimmedUrl = gitUrl.strip();
        String name = (requestedName == null || requestedName.isBlank())
                ? deriveName(trimmedUrl) : requestedName.strip();
        Path workareaPath = uniqueWorkareaPath(ownerUserId, slug(name));

        ProjectRecord project = repository.create(name, trimmedUrl, workareaPath, ownerUserId, Instant.now());
        cloneExecutor.execute(() -> clone(project, githubAccountId));
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
        return createNewProject(org, name, bootstrapTWorkflow, ownerUserId, null);
    }

    /**
     * Same as {@link #createNewProject(String, String, boolean, long)}, acting as
     * {@code githubAccountId} (#550): that account's token is stored as the project's
     * own and {@code gh repo create} plus the first push run with it as
     * {@code GH_TOKEN}. {@code null} means no account was chosen.
     */
    public ProjectRecord createNewProject(String org, String name, boolean bootstrapTWorkflow, long ownerUserId,
            Long githubAccountId) {
        return createNewProject(org, name, bootstrapTWorkflow, ownerUserId, githubAccountId, null);
    }

    /**
     * Same as {@link #createNewProject(String, String, boolean, long, Long)}, created
     * from {@code template} (#536): its name is stored on the row and its body is
     * committed as {@link #TEMPLATE_FILE} before the first push. {@code null} means no
     * template was chosen. The caller ({@code ProjectController}) has already resolved
     * the name through the template listing — nothing from the request reaches a path.
     */
    public ProjectRecord createNewProject(String org, String name, boolean bootstrapTWorkflow, long ownerUserId,
            Long githubAccountId, ProjectTemplate template) {
        String trimmedOrg = org.strip();
        String trimmedName = name.strip();
        String gitUrl = "https://github.com/" + trimmedOrg + "/" + trimmedName + ".git";
        Path workareaPath = uniqueWorkareaPath(ownerUserId, slug(trimmedName));
        Optional<ProjectTemplate> chosen = Optional.ofNullable(template);

        ProjectRecord project = repository.create(trimmedName, gitUrl, workareaPath, ownerUserId, Instant.now(),
                chosen.map(ProjectTemplate::name).orElse(null));
        cloneExecutor.execute(() -> createRepoAndPush(project, trimmedOrg, bootstrapTWorkflow, githubAccountId, chosen));
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
        // Any chosen account (#550) is already stored on the row, so no account here.
        cloneExecutor.execute(() -> clone(cloning, null));
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

    private void clone(ProjectRecord project, Long githubAccountId) {
        log.info("Importing project {} from {} acting as GitHub account {}", project.id(), project.gitUrl(),
                githubAccountId == null ? "none chosen" : githubAccountId);
        try {
            Optional<String> token = Optional.empty();
            if (githubAccountId != null) {
                token = storeTokenForAccount(project, githubAccountId);
                if (token.isEmpty()) {
                    return;
                }
            }
            Files.createDirectories(project.workareaPath().getParent());
            // The credential helper is passed with -c rather than configured first:
            // the destination has no .git yet for a repo-local config to live in, and
            // -c applies for the duration of this one command regardless (#551) --
            // enough for a private repo's clone to authenticate at all.
            ProcessOutcome cloneResult = token.isPresent()
                    ? run(null, tokenEnvironment(token.get()), "git", "-c",
                            "credential.helper=" + CREDENTIAL_HELPER_SCRIPT, "clone", project.gitUrl(),
                            project.workareaPath().toString())
                    : run("git", "clone", project.gitUrl(), project.workareaPath().toString());
            if (cloneResult.failed()) {
                log.warn("git clone failed for project {} from {}: {}", project.id(), project.gitUrl(),
                        cloneResult.describe());
                repository.markFailed(project.id());
                return;
            }
            if (token.isPresent()) {
                ProcessOutcome helperResult = configureCredentialHelper(project.workareaPath());
                if (helperResult.failed()) {
                    log.warn("Could not configure the git credential helper for project {}: {}", project.id(),
                            helperResult.describe());
                    repository.markFailed(project.id());
                    return;
                }
            }
            ProcessOutcome branchResult =
                    run("git", "-C", project.workareaPath().toString(), "branch", "--show-current");
            String branch = branchResult.stdout().strip();
            if (branchResult.failed() || branch.isBlank()) {
                log.warn("Could not determine the default branch for project {} after clone: {}", project.id(),
                        branchResult.describe());
                repository.markFailed(project.id());
                return;
            }
            repository.markReady(project.id(), branch);
            log.info("Project {} ready on branch {}", project.id(), branch);
        } catch (RuntimeException | IOException e) {
            log.error("Import failed for project {} from {}", project.id(), project.gitUrl(), e);
            repository.markFailed(project.id());
        }
    }

    /**
     * The whole create-new sequence after the row exists: the optional chosen account
     * (#550), {@code gh repo create}, then {@link #setUpLocalRepoAndPush}.
     * Package-private so a test can drive it with a stub {@code gh} and a project
     * whose {@code gitUrl} is a throwaway local bare repo, never the real GitHub.
     */
    void createRepoAndPush(ProjectRecord project, String org, boolean bootstrapTWorkflow, Long githubAccountId) {
        createRepoAndPush(project, org, bootstrapTWorkflow, githubAccountId, Optional.empty());
    }

    /** Same, created from {@code template} (#536) when present — see {@link #setUpLocalRepoAndPush}. */
    void createRepoAndPush(ProjectRecord project, String org, boolean bootstrapTWorkflow, Long githubAccountId,
            Optional<ProjectTemplate> template) {
        String repoSpec = org + "/" + project.name();
        log.info("Creating project {} as {} acting as GitHub account {}", project.id(), repoSpec,
                githubAccountId == null ? "none chosen" : githubAccountId);
        try {
            // With a chosen account (#550), gh acts as it through GH_TOKEN in the
            // environment (never on the command line) -- the host's active gh login is
            // left alone. Its token is stored on the row first, so the scope check below
            // examines exactly the token the bootstrap push will use.
            Map<String, String> ghEnv = Map.of();
            if (githubAccountId != null) {
                Optional<String> token = storeTokenForAccount(project, githubAccountId);
                if (token.isEmpty()) {
                    return;
                }
                ghEnv = Map.of("GH_TOKEN", token.get());
            }
            // Checked before the repository is even created, so a token that could
            // never complete the bootstrap push leaves no empty repository behind on
            // GitHub to clean up (#531).
            if (bootstrapTWorkflow && !tokenCanPushWorkflows(project)) {
                repository.markFailed(project.id());
                return;
            }
            ProcessOutcome createResult = run(null, ghEnv, ghExecutable, "repo", "create", repoSpec, "--private");
            if (createResult.failed()) {
                log.warn("gh repo create {} failed for project {} (exit {}): {}", repoSpec, project.id(),
                        createResult.exitCode(), createResult.describe());
                repository.markFailed(project.id());
                return;
            }
            setUpLocalRepoAndPush(project, bootstrapTWorkflow, ghEnv, template);
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
        setUpLocalRepoAndPush(project, bootstrapTWorkflow, Map.of());
    }

    /**
     * Same as {@link #setUpLocalRepoAndPush(ProjectRecord, boolean)}, with
     * {@code pushEnv} added to the first push's environment — {@code GH_TOKEN} for the
     * chosen account (#550), so a host whose git credential helper is {@code gh}
     * authenticates the push as that account too, on top of the token already embedded
     * in the remote URL.
     */
    void setUpLocalRepoAndPush(ProjectRecord project, boolean bootstrapTWorkflow, Map<String, String> pushEnv)
            throws IOException {
        setUpLocalRepoAndPush(project, bootstrapTWorkflow, pushEnv, Optional.empty());
    }

    /**
     * Same as {@link #setUpLocalRepoAndPush(ProjectRecord, boolean, Map)}, committing
     * {@code template}'s body as {@link #TEMPLATE_FILE} (#536) before the push when one
     * was chosen: in the initial commit on the plain path, as one extra
     * {@link #TEMPLATE_COMMIT_SUBJECT} commit on top of the installer's output on the
     * bootstrap path — the installer owns its own first commit, and its tree is only
     * ever appended to, never rewritten.
     */
    void setUpLocalRepoAndPush(ProjectRecord project, boolean bootstrapTWorkflow, Map<String, String> pushEnv,
            Optional<ProjectTemplate> template) throws IOException {
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
                ProcessOutcome install = run(scratch, BOOTSTRAP_GIT_IDENTITY, "bash", "-c", installCommand,
                        "install-t-workflow", T_WORKFLOW_INSTALL_URL, project.name());
                if (install.failed()) {
                    log.warn("t-workflow install failed for project {} (exit {}): {}", project.id(),
                            install.exitCode(), install.describe());
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
            if (template.isPresent()) {
                // The installer's checkout carries no local committer identity of its
                // own (the installer ran under BOOTSTRAP_GIT_IDENTITY), so this commit
                // runs under the same environment for the same reason.
                Files.writeString(workarea.resolve(TEMPLATE_FILE), template.get().body(), StandardCharsets.UTF_8);
                ProcessOutcome addResult = run(workarea, BOOTSTRAP_GIT_IDENTITY, "git", "add", TEMPLATE_FILE);
                if (addResult.failed()) {
                    log.warn("`git add {}` failed for project {}: {}", TEMPLATE_FILE, project.id(),
                            addResult.describe());
                    repository.markFailed(project.id());
                    return;
                }
                ProcessOutcome commitResult =
                        run(workarea, BOOTSTRAP_GIT_IDENTITY, "git", "commit", "-m", TEMPLATE_COMMIT_SUBJECT);
                if (commitResult.failed()) {
                    log.warn("Committing {} failed for project {}: {}", TEMPLATE_FILE, project.id(),
                            commitResult.describe());
                    repository.markFailed(project.id());
                    return;
                }
            }
        } else {
            Files.createDirectories(workarea);
            Files.writeString(workarea.resolve("README.md"), "# " + project.name() + "\n");
            if (template.isPresent()) {
                Files.writeString(workarea.resolve(TEMPLATE_FILE), template.get().body(), StandardCharsets.UTF_8);
            }
            // Nothing guarantees the host's git has a global user.email/user.name
            // configured -- this is the first place the engine ever runs `git commit`
            // itself, so it sets its own local identity rather than assume one.
            ProcessOutcome initResult = run(workarea, "git", "init");
            if (initResult.failed()) {
                log.warn("`git init` failed for project {}: {}", project.id(), initResult.describe());
                repository.markFailed(project.id());
                return;
            }
            ProcessOutcome emailResult = run(workarea, "git", "config", "user.email", "locklane@local");
            if (emailResult.failed()) {
                log.warn("`git config user.email` failed for project {}: {}", project.id(), emailResult.describe());
                repository.markFailed(project.id());
                return;
            }
            ProcessOutcome nameResult = run(workarea, "git", "config", "user.name", "locklane");
            if (nameResult.failed()) {
                log.warn("`git config user.name` failed for project {}: {}", project.id(), nameResult.describe());
                repository.markFailed(project.id());
                return;
            }
            ProcessOutcome addReadmeResult = run(workarea, "git", "add", "README.md");
            if (addReadmeResult.failed()) {
                log.warn("`git add README.md` failed for project {}: {}", project.id(), addReadmeResult.describe());
                repository.markFailed(project.id());
                return;
            }
            if (template.isPresent()) {
                ProcessOutcome addTemplateResult = run(workarea, "git", "add", TEMPLATE_FILE);
                if (addTemplateResult.failed()) {
                    log.warn("`git add {}` failed for project {}: {}", TEMPLATE_FILE, project.id(),
                            addTemplateResult.describe());
                    repository.markFailed(project.id());
                    return;
                }
            }
            ProcessOutcome commitResult = run(workarea, "git", "commit", "-m", "Initial commit");
            if (commitResult.failed()) {
                log.warn("`git commit` failed for project {}: {}", project.id(), commitResult.describe());
                repository.markFailed(project.id());
                return;
            }
        }

        ProcessOutcome branchResult = run(workarea, "git", "branch", "--show-current");
        String branch = branchResult.stdout().strip();
        if (branchResult.exitCode() != 0 || branch.isBlank()) {
            log.warn("Could not determine the default branch for project {}: {}", project.id(),
                    branchResult.describe());
            repository.markFailed(project.id());
            return;
        }

        String remoteUrl = project.gitUrl();
        Optional<String> token = Optional.empty();
        if (remoteUrl.startsWith("https://")) {
            token = resolveGithubToken(project);
            if (token.isEmpty()) {
                log.warn("No GitHub credentials available for project {} ({}) — no GitHub account chosen for it; "
                                + "choose one for this project before retrying",
                        project.id(), remoteUrl);
                repository.markFailed(project.id());
                return;
            }
        }

        ProcessOutcome remoteAddResult = run(workarea, "git", "remote", "add", "origin", remoteUrl);
        if (remoteAddResult.exitCode() != 0) {
            log.warn("Push failed for project {} to {}: {}", project.id(), project.gitUrl(),
                    remoteAddResult.describe());
            repository.markFailed(project.id());
            return;
        }

        // The credential helper (#551), never a token embedded in remoteUrl above --
        // git remote -v shows a plain HTTPS URL, and the token reaches this push only
        // through its own process environment, never a file.
        Map<String, String> effectivePushEnv = pushEnv;
        if (token.isPresent()) {
            ProcessOutcome helperResult = configureCredentialHelper(workarea);
            if (helperResult.failed()) {
                log.warn("Could not configure the git credential helper for project {}: {}", project.id(),
                        helperResult.describe());
                repository.markFailed(project.id());
                return;
            }
            Map<String, String> merged = new LinkedHashMap<>(pushEnv);
            merged.putAll(tokenEnvironment(token.get()));
            effectivePushEnv = merged;
        }

        ProcessOutcome pushResult = run(workarea, effectivePushEnv, "git", "push", "-u", "origin", branch);
        if (pushResult.exitCode() != 0) {
            log.warn("Push failed for project {} to {}: {}", project.id(), project.gitUrl(), pushResult.describe());
            repository.markFailed(project.id());
            return;
        }

        repository.markReady(project.id(), branch);
        log.info("Project {} ready on branch {}", project.id(), branch);
    }

    /** Configures {@link #CREDENTIAL_HELPER_SCRIPT} repo-locally in {@code workarea} (#551). */
    private static ProcessOutcome configureCredentialHelper(Path workarea) {
        return run(workarea, "git", "config", "credential.helper", CREDENTIAL_HELPER_SCRIPT);
    }

    private static Map<String, String> tokenEnvironment(String token) {
        return Map.of("GH_TOKEN", token);
    }

    /**
     * Whether the token the bootstrap push will authenticate with -- the chosen
     * account's own token (#550), resolved exactly as {@link #resolveGithubToken}
     * resolves it, so the answer does not depend on which credential source wins --
     * carries the {@link #WORKFLOW_SCOPE} that pushing {@code .github/workflows/ci.yml}
     * requires (#531). The scopes checked are the ones {@code GhAccountsService}
     * captured when the account was added, never re-queried here. Logs the one WARN an
     * operator needs, naming the missing scope and the command that grants it, when it
     * does not.
     * <p>
     * Deliberately fails <em>open</em> when the answer is unknowable: no account
     * chosen at all is left to {@link #setUpLocalRepoAndPush}'s existing "No GitHub
     * credentials" path, and an account whose scopes were never captured (defensive
     * only — {@code GhAccountsService} always records them) proceeds exactly as
     * before this check existed. Only a positively reported scope list that lacks
     * {@code workflow} refuses. Package-private so a test can drive each branch
     * directly without reaching {@code gh repo create}.
     */
    boolean tokenCanPushWorkflows(ProjectRecord project) {
        Optional<GhAccount> account = repository.findGithubAccountId(project.id())
                .flatMap(id -> ghAccountRepository.findById(id));
        if (account.isEmpty()) {
            return true;
        }
        if (account.get().scopes().isEmpty()) {
            log.info("The scopes of the GitHub account for project {} ({}) are unknown; proceeding with the "
                    + "t-workflow bootstrap without checking for the `{}` scope", project.id(), project.name(),
                    WORKFLOW_SCOPE);
            return true;
        }
        if (account.get().hasWorkflowScope()) {
            return true;
        }
        log.warn("Refusing to bootstrap project {} ({}) with t-workflow: its GitHub account ({}) lacks the `{}` "
                        + "scope that pushing .github/workflows/ci.yml requires (its scopes: {}). Grant it with "
                        + "`{}` and add the account again",
                project.id(), project.name(), account.get().login(), WORKFLOW_SCOPE,
                String.join(", ", account.get().scopes()), GRANT_WORKFLOW_SCOPE_COMMAND);
        return false;
    }

    /**
     * Resolves one of the GitHub accounts the caller has signed in to Locklane
     * (#550) and stores its token, encrypted, as the project's own (#81), so every
     * later issue/PR fetch and push acts as that account. Empty — with the project
     * marked FAILED and a WARN naming the account id — when it does not exist or its
     * token cannot be looked up (an account removed between the request and this
     * async step; the ordinary case, an unauthorized or unknown account id, is
     * refused synchronously by {@code ProjectController} before a project row ever
     * exists). The token itself never reaches a log line.
     */
    private Optional<String> storeTokenForAccount(ProjectRecord project, long githubAccountId) {
        Optional<String> encryptedToken = ghAccountRepository.findEncryptedToken(githubAccountId);
        if (encryptedToken.isEmpty()) {
            log.warn("No such GitHub account {} for project {} — it may have been removed since the project was "
                    + "requested", githubAccountId, project.id());
            repository.markFailed(project.id());
            return Optional.empty();
        }
        repository.setGithubAccountId(project.id(), githubAccountId);
        return Optional.of(tokenCipher.decrypt(encryptedToken.get()));
    }

    /**
     * The GitHub token to embed as HTTPS Basic-auth credentials in the push URL —
     * {@code x-access-token} is the conventional username GitHub accepts alongside a
     * PAT/installation token as the password — so the push authenticates on its own
     * rather than depending on whatever git/SSH credential setup happens to already
     * exist on the host (#505). The project's chosen account's token (#550) — a
     * freshly created project with none chosen has no credentials at all here, unlike
     * before #550, which fell back to whatever identity the host's own {@code gh} was
     * logged in as.
     */
    private Optional<String> resolveGithubToken(ProjectRecord project) {
        OptionalLong accountId = toOptionalLong(repository.findGithubAccountId(project.id()));
        if (accountId.isEmpty()) {
            return Optional.empty();
        }
        return ghAccountRepository.findEncryptedToken(accountId.getAsLong()).map(tokenCipher::decrypt);
    }

    private static OptionalLong toOptionalLong(Optional<Long> value) {
        return value.map(OptionalLong::of).orElseGet(OptionalLong::empty);
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
                    // silent: best-effort cleanup — a leftover file here doesn't block
                    // anything.
                }
            });
        } catch (IOException ignored) {
            // silent: same — cleanup is best-effort.
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

    private static ProcessOutcome run(String... command) {
        return run(null, command);
    }

    /** Same as {@link #run(String...)}, run in {@code cwd} instead of the engine's own working directory. */
    private static ProcessOutcome run(Path cwd, String... command) {
        return run(cwd, Map.of(), command);
    }

    /** Same as {@link #run(Path, String...)}, with {@code env} added to the child's environment. */
    private static ProcessOutcome run(Path cwd, Map<String, String> env, String... command) {
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
            return new ProcessOutcome(exit, out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProjectCheckoutException("Interrupted while running " + command[0], e);
        } catch (IOException e) {
            throw new ProjectCheckoutException("Could not run " + command[0] + " — is it installed and on PATH?", e);
        }
    }

    public static class ProjectCheckoutException extends RuntimeException {
        public ProjectCheckoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
