package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #687: a host's own credential helper — configured in a scope git reads before a
 * command-line {@code -c} override or a repo-local config entry, such as a global
 * {@code ~/.gitconfig} (standing in here for macOS's system-wide {@code osxkeychain},
 * which a test cannot safely configure) — answers first and is never replaced by a
 * later, non-empty {@code credential.helper} entry; only an empty entry resets the
 * list. Each of the three shapes {@link GitCredential} and {@link
 * ProjectCheckoutService#configureCredentialHelper} install must defeat such a decoy
 * with a real {@code git} process, never merely by shape. Runs entirely against a
 * throwaway {@code HOME} and a throwaway local bare repo — the developer's own {@code
 * ~/.gitconfig} is never read or written.
 */
class GitCredentialHostHelperTest {

    private static final String TOKEN = "locklane-token";
    private static final String CREDENTIAL_REQUEST = "protocol=https\nhost=github.com\n\n";

    @Test
    void theInlineCommandFormDefeatsAHostConfiguredCredentialHelper(@TempDir Path tmp) throws Exception {
        Path home = decoyHome(tmp);
        Path marker = home.resolve("decoy-called");
        installDecoyHelper(tmp, home, marker);
        Path bareRepo = bareRepo(tmp);

        GitCredential credential = GitCredential.forRemote("https://github.com/org/repo.git", Optional.of(TOKEN));
        Map<String, String> env = baseEnvironment(home);
        env.putAll(credential.environment());

        String output = runCredentialFill(bareRepo, env, credential.command("credential", "fill"));

        assertAnsweredAsTheProjectAccount(output);
        assertThat(Files.exists(marker)).as("the decoy helper was never invoked").isFalse();
    }

    @Test
    void theSessionEnvironmentFormDefeatsAHostConfiguredCredentialHelper(@TempDir Path tmp) throws Exception {
        Path home = decoyHome(tmp);
        Path marker = home.resolve("decoy-called");
        installDecoyHelper(tmp, home, marker);
        Path bareRepo = bareRepo(tmp);

        GitCredential credential = GitCredential.forRemote("https://github.com/org/repo.git", Optional.of(TOKEN));
        Map<String, String> env = baseEnvironment(home);
        env.putAll(credential.sessionEnvironment());

        String output = runCredentialFill(bareRepo, env, "git", "credential", "fill");

        assertAnsweredAsTheProjectAccount(output);
        assertThat(Files.exists(marker)).as("the decoy helper was never invoked").isFalse();
    }

    @Test
    void theRepoLocalConfigFormDefeatsAHostConfiguredCredentialHelper(@TempDir Path tmp) throws Exception {
        Path home = decoyHome(tmp);
        Path marker = home.resolve("decoy-called");
        installDecoyHelper(tmp, home, marker);
        Path bareRepo = bareRepo(tmp);

        // Writing the repo-local entries needs no decoy HOME of its own -- it only
        // ever adds config, never queries a credential.
        assertThat(ProjectCheckoutService.configureCredentialHelper(bareRepo).failed()).isFalse();

        Map<String, String> env = baseEnvironment(home);
        env.put("GH_TOKEN", TOKEN);

        String output = runCredentialFill(bareRepo, env, "git", "credential", "fill");

        assertAnsweredAsTheProjectAccount(output);
        assertThat(Files.exists(marker)).as("the decoy helper was never invoked").isFalse();
    }

    private static void assertAnsweredAsTheProjectAccount(String output) {
        assertThat(output).contains("username=x-access-token").contains("password=" + TOKEN)
                .doesNotContain("someone-else").doesNotContain("not-the-locklane-token");
    }

    /** A throwaway {@code HOME} -- never the developer's own. */
    private static Path decoyHome(Path tmp) throws IOException {
        Path home = tmp.resolve("home");
        Files.createDirectories(home);
        return home;
    }

    /**
     * A global {@code credential.helper} in {@code home}'s own {@code .gitconfig} that
     * records being called and answers with a fixed, wrong username/password -- git
     * reads this scope before a command-line {@code -c} override or a repo-local entry
     * that comes after it, the same relative position macOS's system-wide {@code
     * osxkeychain} occupies ahead of Locklane's own helper (#687).
     */
    private static void installDecoyHelper(Path tmp, Path home, Path marker) throws IOException, InterruptedException {
        String decoy = "!f() { echo called >> '" + marker + "'; echo username=someone-else; "
                + "echo password=not-the-locklane-token; }; f";
        run(tmp, baseEnvironment(home), "git", "config", "--global", "credential.helper", decoy);
    }

    /** A throwaway local bare repo -- no network, nothing on it but the decoy above ever needs to answer for. */
    private static Path bareRepo(Path tmp) throws IOException, InterruptedException {
        Path bare = tmp.resolve("bare.git");
        run(tmp, baseEnvironment(tmp.resolve("home")), "git", "init", "--bare", "-q", bare.toString());
        return bare;
    }

    /**
     * {@code HOME} plus {@code PATH} and nothing else -- a from-scratch environment so
     * no ambient {@code GIT_CONFIG_*} or credential variable (this very engine's own
     * session might carry one) leaks into the subprocess under test.
     */
    private static Map<String, String> baseEnvironment(Path home) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", home.toString());
        env.put("PATH", System.getenv("PATH"));
        return env;
    }

    private static String runCredentialFill(Path cwd, Map<String, String> env, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process = builder.start();
        process.getOutputStream().write(CREDENTIAL_REQUEST.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    private static void run(Path cwd, Map<String, String> env, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
        }
    }
}
