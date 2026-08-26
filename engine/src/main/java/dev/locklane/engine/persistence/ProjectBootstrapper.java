package dev.locklane.engine.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Registers the engine's own existing checkout ({@code locklane.project-root}) as a
 * project on first run, so worktree/session creation — which now always resolves a
 * project's workarea (#43) — keeps working with no visible change until #44/#45 give
 * the user a way to add/pick projects themselves. Mirrors {@code UserBootstrapper}'s
 * shape: a no-op once any project exists.
 */
@Component
public class ProjectBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProjectBootstrapper.class);

    private final ProjectRepository projectRepository;
    private final Path projectRoot;

    public ProjectBootstrapper(ProjectRepository projectRepository, @Value("${locklane.project-root}") String projectRoot) {
        this.projectRepository = projectRepository;
        this.projectRoot = Path.of(projectRoot).normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!projectRepository.findAll().isEmpty()) {
            return;
        }
        Path fileName = projectRoot.getFileName();
        String name = fileName != null ? fileName.toString() : "project";
        String gitUrl = remoteUrl().orElse(projectRoot.toString());
        String defaultBranch = currentBranch().orElse("main");
        projectRepository.createReady(name, gitUrl, projectRoot, defaultBranch, Instant.now());
        log.info("No projects existed yet — registered the engine's own checkout '{}' as project '{}'.",
                projectRoot, name);
    }

    private Optional<String> remoteUrl() {
        return run("git", "-C", projectRoot.toString(), "remote", "get-url", "origin").filter(s -> !s.isBlank());
    }

    private Optional<String> currentBranch() {
        return run("git", "-C", projectRoot.toString(), "branch", "--show-current").filter(s -> !s.isBlank());
    }

    private static Optional<String> run(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int exit = process.waitFor();
            return exit == 0 ? Optional.of(out) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
