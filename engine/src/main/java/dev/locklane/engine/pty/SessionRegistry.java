package dev.locklane.engine.pty;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one {@link PtySession} per worktree. Attaching to a worktree that already
 * has a running session returns that same session rather than starting a new
 * process — the reattachment behavior a network transport (a later task) will sit
 * on top of.
 */
@Service
public class SessionRegistry {

    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
    private final String[] shellCommand;

    public SessionRegistry() {
        this.shellCommand = defaultShellCommand();
    }

    /** Returns the worktree's running session, starting one if none exists yet. */
    public PtySession attach(String worktreeId, Path workingDirectory) {
        return sessions.computeIfAbsent(worktreeId,
                id -> new PtySession(id, workingDirectory, shellCommand, System.getenv()));
    }

    public Optional<PtySession> find(String worktreeId) {
        return Optional.ofNullable(sessions.get(worktreeId));
    }

    private static String[] defaultShellCommand() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
            shell = Files.isExecutable(Path.of("/bin/bash")) ? "/bin/bash" : "/bin/sh";
        }
        return new String[] {shell, "-i"};
    }

    /** Stops every running session. Orphan processes are not left behind on shutdown. */
    @PreDestroy
    void closeAll() {
        sessions.values().forEach(PtySession::close);
        sessions.clear();
    }
}
