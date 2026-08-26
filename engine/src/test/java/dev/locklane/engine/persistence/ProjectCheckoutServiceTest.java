package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises real {@code git clone} against a throwaway local repository (no
 * network) for genuine confidence (#42, mirroring {@code WorktreeCreationServiceTest}
 * for #20). {@code cloneExecutor} runs same-thread ({@code Runnable::run}) so every
 * assertion below sees the clone's outcome without polling.
 */
class ProjectCheckoutServiceTest {

    @Test
    void deriveNameTakesTheLastPathSegmentAndDropsDotGit() {
        assertThat(ProjectCheckoutService.deriveName("https://github.com/foo/bar.git")).isEqualTo("bar");
        assertThat(ProjectCheckoutService.deriveName("git@github.com:foo/bar.git")).isEqualTo("bar");
        assertThat(ProjectCheckoutService.deriveName("https://example.com/repo/")).isEqualTo("repo");
    }

    @Test
    void slugLowercasesAndDashesNonAlnumRuns() {
        assertThat(ProjectCheckoutService.slug("My Cool Project!")).isEqualTo("my-cool-project");
        assertThat(ProjectCheckoutService.slug("---")).isEqualTo("project");
    }

    @Test
    void createsARealCloneAndDiscoversTheActualDefaultBranch(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "trunk");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "myproj");

        assertThat(project.name()).isEqualTo("myproj");
        Path workarea = tmp.resolve("workarea").resolve("myproj");
        assertThat(workarea).isDirectory();

        ProjectRepository repository = repositoryOver(tmp);
        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.READY);
        assertThat(found.defaultBranch()).isEqualTo("trunk");
    }

    @Test
    void aBlankNameIsDerivedFromTheGitUrl(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject(origin.toString(), "  ");

        assertThat(project.name()).isEqualTo(ProjectCheckoutService.deriveName(origin.toString()));
    }

    @Test
    void aFailedCloneMarksTheProjectFailedAndLeavesTheGitUrlIntact(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        ProjectRecord project = service.createProject("/does/not/exist", "broken");

        ProjectRepository repository = repositoryOver(tmp);
        ProjectRecord found = repository.findById(project.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ProjectStatus.FAILED);
        assertThat(found.defaultBranch()).isNull();
        assertThat(found.gitUrl()).isEqualTo("/does/not/exist");
    }

    @Test
    void aNameCollisionGetsANumericSuffix(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);

        ProjectRecord first = service.createProject(origin.toString(), "dup");
        ProjectRecord second = service.createProject(origin.toString(), "dup");

        assertThat(first.workareaPath()).isNotEqualTo(second.workareaPath());
        assertThat(second.workareaPath().getFileName().toString()).isEqualTo("dup-2");
    }

    @Test
    void retryReClonesAFailedProject(@TempDir Path tmp) throws Exception {
        ProjectCheckoutService service = service(tmp);
        ProjectRecord failed = service.createProject("/does/not/exist", "will-retry");
        assertThat(repositoryOver(tmp).findById(failed.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);

        // Point retry at a real repo isn't possible without changing the stored git
        // URL (out of scope), so this covers what retry can control: it re-runs the
        // clone against the same (still-broken) URL and the project stays FAILED,
        // not stuck CLONING forever.
        Optional<ProjectRecord> retried = service.retry(failed.id());

        assertThat(retried).isPresent();
        assertThat(repositoryOver(tmp).findById(failed.id()).orElseThrow().status()).isEqualTo(ProjectStatus.FAILED);
    }

    @Test
    void retryOnAReadyProjectIsEmpty(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);
        ProjectRecord ready = service.createProject(origin.toString(), "already-ready");

        assertThat(service.retry(ready.id())).isEmpty();
    }

    @Test
    void retryOnAnUnknownProjectIsEmpty(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        assertThat(service.retry(999)).isEmpty();
    }

    @Test
    void deleteRemovesTheProjectAndItsWorkareaDirectory(@TempDir Path tmp) throws Exception {
        Path origin = initBareOriginWithDefaultBranch(tmp, "main");
        ProjectCheckoutService service = service(tmp);
        ProjectRecord project = service.createProject(origin.toString(), "to-delete");
        assertThat(project.workareaPath()).isDirectory();

        boolean deleted = service.delete(project.id());

        assertThat(deleted).isTrue();
        assertThat(repositoryOver(tmp).findById(project.id())).isEmpty();
        assertThat(project.workareaPath()).doesNotExist();
    }

    @Test
    void deletingAnUnknownProjectReturnsFalse(@TempDir Path tmp) {
        ProjectCheckoutService service = service(tmp);

        assertThat(service.delete(999)).isFalse();
    }

    private static ProjectCheckoutService service(Path tmp) {
        return new ProjectCheckoutService(repositoryOver(tmp), tmp.resolve("workarea").toString(), Runnable::run);
    }

    private static ProjectRepository repositoryOver(Path tmp) {
        return TestSqliteDatabases.newProjectRepository(tmp);
    }

    /** A minimal local bare repo with a controllable default branch name — no network. */
    private static Path initBareOriginWithDefaultBranch(Path tmp, String defaultBranch)
            throws IOException, InterruptedException {
        Path bare = tmp.resolve("origin-" + defaultBranch + ".git");
        Path seed = tmp.resolve("seed-" + defaultBranch);
        Files.createDirectories(seed);

        run(tmp, "git", "init", "--bare", "-b", defaultBranch, bare.toString());
        run(tmp, "git", "init", "-b", defaultBranch, seed.toString());
        run(seed, "git", "config", "user.email", "test@example.com");
        run(seed, "git", "config", "user.name", "Test");
        Files.writeString(seed.resolve("README.md"), "seed");
        run(seed, "git", "add", "README.md");
        run(seed, "git", "commit", "-m", "initial commit");
        run(seed, "git", "remote", "add", "origin", bare.toString());
        run(seed, "git", "push", "origin", defaultBranch);
        return bare;
    }

    private static void run(Path cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
        }
    }
}
