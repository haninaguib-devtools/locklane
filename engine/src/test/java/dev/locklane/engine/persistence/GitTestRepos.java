package dev.locklane.engine.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A minimal throwaway local git repository — a bare "origin" and a pushed "main", no
 * network — for tests that exercise real {@code git worktree add} commands rather than
 * mocking them (#20). Extracted from {@code WorktreeCreationServiceTest} when {@code
 * ProjectConsoleServiceTest}/{@code ProjectConsoleControllerTest} needed the exact same
 * setup for #314's project-console worktrees.
 */
final class GitTestRepos {

    private GitTestRepos() {
    }

    /** A minimal local repo with an "origin" remote and a main branch — no network. */
    static Path initTestRepo(Path dir) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path bare = dir.resolve("origin.git");
        Path work = dir.resolve("work");
        Files.createDirectories(work);

        run(dir, "git", "init", "--bare", "-b", "main", bare.toString());
        run(dir, "git", "init", "-b", "main", work.toString());
        run(work, "git", "config", "user.email", "test@example.com");
        run(work, "git", "config", "user.name", "Test");
        Files.writeString(work.resolve("README.md"), "test repo");
        run(work, "git", "add", "README.md");
        run(work, "git", "commit", "-m", "initial commit");
        run(work, "git", "remote", "add", "origin", bare.toString());
        run(work, "git", "push", "origin", "main");
        run(work, "git", "branch", "--set-upstream-to=origin/main", "main");
        return work;
    }

    static String currentBranch(Path worktree) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "-C", worktree.toString(), "branch", "--show-current").start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return out;
    }

    /** Local branches matching {@code pattern} (e.g. {@code "console/*"}), one per line, trimmed. */
    static List<String> branchList(Path repo, String pattern) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "-C", repo.toString(), "branch", "--list", pattern).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
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
