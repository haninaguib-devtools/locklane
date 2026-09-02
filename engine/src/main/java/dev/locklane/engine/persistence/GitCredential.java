package dev.locklane.engine.persistence;

import dev.locklane.engine.security.TokenCipher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one way the engine hands a project's GitHub token to a git subprocess that talks
 * to the remote (#569): every engine-run {@code git clone}, {@code fetch}, and
 * {@code push} builds its command line through {@link #command} and its environment
 * from {@link #environment()}, so a private HTTPS repository authenticates on a host
 * with no git credential helper and no SSH key of its own.
 *
 * <p>The token never appears on the command line, in a log line, or in a stored
 * remote URL: git is pointed at an inline credential helper ({@code -c
 * credential.helper=...}, in force for that one command only) that answers with the
 * {@code GH_TOKEN} variable from the child's own process environment. That is the same
 * helper #551 configures repo-locally after a clone; passing it inline on every remote
 * command as well means a checkout that predates #551, or whose config was reset,
 * still authenticates.
 *
 * <p>Only an HTTPS remote gets the injection. An SSH remote ({@code git@github.com:...})
 * keeps git's own key handling — the helper would never be consulted, and the token
 * has no business in that process — and a project with no stored token gets plain git,
 * exactly as before, so ambient host credentials still apply where they exist.
 */
public record GitCredential(List<String> configArguments, Map<String, String> environment) {

    /** The helper script #551 introduced: username {@code x-access-token}, password from {@code GH_TOKEN}. */
    public static final String HELPER_SCRIPT = "!f() { echo username=x-access-token; echo password=$GH_TOKEN; }; f";

    /** Plain git: no config override, nothing added to the environment. */
    public static final GitCredential NONE = new GitCredential(List.of(), Map.of());

    public GitCredential {
        configArguments = List.copyOf(configArguments);
        environment = Map.copyOf(environment);
    }

    /**
     * The credential for {@code remoteUrl}: the inline helper plus {@code GH_TOKEN}
     * when the remote is HTTPS and a token is present; {@link #NONE} for an SSH (or
     * any non-HTTPS) remote, and for a missing or blank token.
     */
    public static GitCredential forRemote(String remoteUrl, Optional<String> token) {
        if (!isHttps(remoteUrl) || token.isEmpty() || token.get().isBlank()) {
            return NONE;
        }
        return new GitCredential(List.of("-c", "credential.helper=" + HELPER_SCRIPT), Map.of("GH_TOKEN", token.get()));
    }

    /**
     * The credential for {@code projectId}'s own remote and chosen GitHub account
     * (#550): {@link #NONE} for an unknown project, a project with no account chosen,
     * an account whose token is gone, or a non-HTTPS remote.
     */
    public static GitCredential forProject(long projectId, ProjectRepository projectRepository,
            GhAccountRepository ghAccountRepository, TokenCipher tokenCipher) {
        Optional<ProjectRecord> project = projectRepository.findById(projectId);
        if (project.isEmpty()) {
            return NONE;
        }
        Optional<String> token = projectRepository.findGithubAccountId(projectId)
                .flatMap(ghAccountRepository::findEncryptedToken)
                .map(tokenCipher::decrypt);
        return forRemote(project.get().gitUrl(), token);
    }

    /** Whether {@code remoteUrl} is one git would authenticate over HTTPS — the only transport the helper serves. */
    public static boolean isHttps(String remoteUrl) {
        return remoteUrl != null && remoteUrl.strip().toLowerCase().startsWith("https://");
    }

    /** Whether this credential actually injects anything — false for {@link #NONE}. */
    public boolean present() {
        return !environment.isEmpty();
    }

    /**
     * {@code git <config overrides> <arguments...>} — the full command line for one
     * remote-touching git call, e.g. {@code command("-C", root, "fetch", "--prune",
     * "origin")}. Run it with {@link #environment()} added to the child's environment.
     */
    public String[] command(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(configArguments);
        command.addAll(List.of(arguments));
        return command.toArray(String[]::new);
    }
}
