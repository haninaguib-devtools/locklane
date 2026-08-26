package dev.locklane.engine.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectBootstrapperTest {

    private static final ApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @Test
    void registersTheExistingCheckoutAsAReadyProjectWhenNoneExist(@TempDir Path tmp) throws Exception {
        Path checkout = initRepoWithBranch(tmp, "trunk");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        ProjectBootstrapper bootstrapper = new ProjectBootstrapper(projectRepository, checkout.toString());

        bootstrapper.run(NO_ARGS);

        List<ProjectRecord> projects = projectRepository.findAll();
        assertThat(projects).hasSize(1);
        ProjectRecord project = projects.get(0);
        assertThat(project.workareaPath()).isEqualTo(checkout);
        assertThat(project.status()).isEqualTo(ProjectStatus.READY);
        assertThat(project.defaultBranch()).isEqualTo("trunk");
        assertThat(project.name()).isEqualTo(checkout.getFileName().toString());
    }

    @Test
    void isANoOpWhenAProjectAlreadyExists(@TempDir Path tmp) throws Exception {
        Path checkout = initRepoWithBranch(tmp, "main");
        ProjectRepository projectRepository = TestSqliteDatabases.newProjectRepository(tmp);
        projectRepository.create("already-here", "url", tmp.resolve("elsewhere"), java.time.Instant.now());
        ProjectBootstrapper bootstrapper = new ProjectBootstrapper(projectRepository, checkout.toString());

        bootstrapper.run(NO_ARGS);

        assertThat(projectRepository.findAll()).hasSize(1);
        assertThat(projectRepository.findAll().get(0).name()).isEqualTo("already-here");
    }

    private static Path initRepoWithBranch(Path tmp, String branch) throws IOException, InterruptedException {
        Path checkout = tmp.resolve("checkout");
        Files.createDirectories(checkout);
        run(checkout, "git", "init", "-b", branch, checkout.toString());
        run(checkout, "git", "config", "user.email", "test@example.com");
        run(checkout, "git", "config", "user.name", "Test");
        Files.writeString(checkout.resolve("README.md"), "test repo");
        run(checkout, "git", "add", "README.md");
        run(checkout, "git", "commit", "-m", "initial commit");
        return checkout;
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
