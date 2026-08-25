package dev.locklane.engine.pty;

import dev.locklane.engine.persistence.WorktreeSessionRecord;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one {@link PtySession} per worktree. Attaching to a worktree that already
 * has a running session returns that same session rather than starting a new
 * process. Every attach is also recorded in {@link WorktreeSessionRepository}, so a
 * worktree's last-known state — which directory it ran in, when it was last
 * attached to — survives a server restart even though the live process does not
 * (#6).
 */
@Service
public class SessionRegistry {

    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
    private final String[] shellCommand;
    private final WorktreeSessionRepository repository;

    public SessionRegistry(WorktreeSessionRepository repository) {
        this.repository = repository;
        this.shellCommand = defaultShellCommand();
    }

    /** Returns the worktree's running session, starting one if none exists yet. */
    public PtySession attach(String worktreeId, Path workingDirectory) {
        PtySession session = sessions.computeIfAbsent(worktreeId,
                id -> new PtySession(id, workingDirectory, shellCommand, System.getenv()));
        repository.recordAttach(worktreeId, workingDirectory, Instant.now());
        return session;
    }

    public Optional<PtySession> find(String worktreeId) {
        return Optional.ofNullable(sessions.get(worktreeId));
    }

    /**
     * The working directory last recorded for a worktree, even when this process has
     * no live session for it right now — the case right after a restart, before
     * anyone has reattached.
     */
    public Optional<Path> lastKnownWorkingDirectory(String worktreeId) {
        return repository.find(worktreeId).map(WorktreeSessionRecord::workingDirectory);
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
