package dev.locklane.engine.persistence;

import dev.locklane.engine.github.GhAccount;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #569: the one shared way every engine git clone/fetch/push receives the project's
 * token — over HTTPS with a token present, and never otherwise.
 */
class GitCredentialTest {

    @Test
    void anHttpsRemoteWithATokenGetsTheInlineHelperAndTheTokenInTheEnvironment() {
        GitCredential credential = GitCredential.forRemote("https://github.com/org/repo.git", Optional.of("ghp_secret"));

        assertThat(credential.present()).isTrue();
        assertThat(credential.configArguments())
                .containsExactly("-c", GitCredential.HELPER_KEY + "=" + GitCredential.HELPER_SCRIPT);
        assertThat(credential.environment()).containsExactly(java.util.Map.entry("GH_TOKEN", "ghp_secret"));
    }

    @Test
    void theTokenNeverAppearsOnTheCommandLine() {
        GitCredential credential = GitCredential.forRemote("https://github.com/org/repo.git", Optional.of("ghp_secret"));

        String[] command = credential.command("clone", "https://github.com/org/repo.git", "/tmp/dest");

        assertThat(command).startsWith("git", "-c", GitCredential.HELPER_KEY + "=" + GitCredential.HELPER_SCRIPT)
                .endsWith("clone", "https://github.com/org/repo.git", "/tmp/dest");
        assertThat(String.join(" ", command)).doesNotContain("ghp_secret");
    }

    @Test
    void anSshRemoteGetsPlainGitEvenWithAToken() {
        GitCredential credential = GitCredential.forRemote("git@github.com:org/repo.git", Optional.of("ghp_secret"));

        assertThat(credential).isSameAs(GitCredential.NONE);
        assertThat(credential.command("fetch", "--prune", "origin")).containsExactly("git", "fetch", "--prune", "origin");
        assertThat(credential.environment()).isEmpty();
    }

    @Test
    void anHttpsRemoteWithNoTokenGetsPlainGit() {
        assertThat(GitCredential.forRemote("https://github.com/org/repo.git", Optional.empty()))
                .isSameAs(GitCredential.NONE);
        assertThat(GitCredential.forRemote("https://github.com/org/repo.git", Optional.of("  ")))
                .isSameAs(GitCredential.NONE);
    }

    @Test
    void forProjectResolvesTheStoredAccountsTokenForAnHttpsRemote(@TempDir Path tmp) throws IOException {
        ProjectRepository projects = TestSqliteDatabases.newProjectRepository(tmp);
        GhAccountRepository accounts = TestSqliteDatabases.newGhAccountRepository(tmp);
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        ProjectRecord project = projects.create("p", "https://github.com/org/p.git", tmp.resolve("p"), 1L, Instant.now());
        GhAccount account = accounts.insert(1L, "work", cipher.encrypt("ghp_secret"), Set.of("repo"), Instant.now());
        projects.setGithubAccountId(project.id(), account.id());

        GitCredential credential = GitCredential.forProject(project.id(), projects, accounts, cipher);

        assertThat(credential.present()).isTrue();
        assertThat(credential.environment()).containsEntry("GH_TOKEN", "ghp_secret");
    }

    @Test
    void forProjectIsPlainGitWithNoAccountChosenOrAnUnknownProject(@TempDir Path tmp) throws IOException {
        ProjectRepository projects = TestSqliteDatabases.newProjectRepository(tmp);
        GhAccountRepository accounts = TestSqliteDatabases.newGhAccountRepository(tmp);
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        ProjectRecord project = projects.create("p", "https://github.com/org/p.git", tmp.resolve("p"), 1L, Instant.now());

        assertThat(GitCredential.forProject(project.id(), projects, accounts, cipher)).isSameAs(GitCredential.NONE);
        assertThat(GitCredential.forProject(999L, projects, accounts, cipher)).isSameAs(GitCredential.NONE);
    }

    @Test
    void forProjectIsPlainGitForAnSshRemoteEvenWithAnAccount(@TempDir Path tmp) throws IOException {
        ProjectRepository projects = TestSqliteDatabases.newProjectRepository(tmp);
        GhAccountRepository accounts = TestSqliteDatabases.newGhAccountRepository(tmp);
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(tmp.toString()));
        ProjectRecord project = projects.create("p", "git@github.com:org/p.git", tmp.resolve("p"), 1L, Instant.now());
        GhAccount account = accounts.insert(1L, "work", cipher.encrypt("ghp_secret"), Set.of("repo"), Instant.now());
        projects.setGithubAccountId(project.id(), account.id());

        assertThat(GitCredential.forProject(project.id(), projects, accounts, cipher)).isSameAs(GitCredential.NONE);
    }

    // #572: the same credential, as the environment an interactive session inherits.

    @Test
    void sessionEnvironmentInstallsTheHelperThroughGitConfigVariables() {
        GitCredential credential = GitCredential.forRemote("https://github.com/org/repo.git", Optional.of("ghp_secret"));

        assertThat(credential.sessionEnvironment()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "GH_TOKEN", "ghp_secret",
                "GIT_CONFIG_COUNT", "1",
                "GIT_CONFIG_KEY_0", GitCredential.HELPER_KEY,
                "GIT_CONFIG_VALUE_0", GitCredential.HELPER_SCRIPT));
        // The token is only ever the GH_TOKEN value the helper script reads at run time.
        assertThat(credential.sessionEnvironment().get("GIT_CONFIG_VALUE_0")).doesNotContain("ghp_secret");
    }

    @Test
    void sessionEnvironmentIsEmptyForPlainGit() {
        assertThat(GitCredential.NONE.sessionEnvironment()).isEmpty();
        assertThat(GitCredential.forRemote("git@github.com:org/repo.git", Optional.of("ghp_secret")).sessionEnvironment())
                .isEmpty();
    }
}
