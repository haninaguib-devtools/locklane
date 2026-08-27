package dev.locklane.engine.pty;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.WorktreeSessionRecord;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.ws.EventBroadcaster;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one {@link PtySession} per session id. A session's id is its own —
 * independent of the worktree/working-directory it runs in — so more than one
 * session can point at the same directory (including the main checkout, with no
 * worktree involved at all). Attaching to a session id that already has a running
 * session returns that same session rather than starting a new process. Every
 * attach is also recorded in {@link WorktreeSessionRepository}, so a session's
 * last-known state — which directory it ran in, when it was last attached to —
 * survives a server restart even though the live process does not (#6).
 */
@Service
public class SessionRegistry {

    // pty4j's own default when nobody says otherwise — used only for a session's
    // first attach; a browser terminal reports its real size moments later (#62).
    private static final int DEFAULT_COLUMNS = 80;
    private static final int DEFAULT_ROWS = 24;

    // #130: how often every live session's quiescence fallback is re-checked — tight
    // enough that a session which just went quiet is caught within about a second of
    // crossing PtySession.QUIESCENCE_THRESHOLD_MS, not a user-visible extra delay.
    private static final long QUIESCENCE_POLL_MS = 1000;

    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
    private final String[] shellCommand;
    private final WorktreeSessionRepository repository;
    private final EventBroadcaster eventBroadcaster;

    @Autowired
    public SessionRegistry(WorktreeSessionRepository repository, EventBroadcaster eventBroadcaster) {
        this.repository = repository;
        this.eventBroadcaster = eventBroadcaster;
        this.shellCommand = defaultShellCommand();
    }

    /**
     * Test-only: a broadcaster with no registered sessions, since most tests here
     * don't care about the events channel (#130's own attention tests subscribe on
     * the {@link PtySession} directly instead).
     */
    public SessionRegistry(WorktreeSessionRepository repository) {
        this(repository, new EventBroadcaster(new ObjectMapper()));
    }

    /**
     * Returns the session's running process, starting one if none exists yet. A
     * {@code null} launch command falls back to the default shell — the launch
     * command only matters for a session's first attach; a reattach reaches the
     * process already running, whatever it was started with. {@code ownerUsername}
     * (nullable) is stamped as the session's owner on a first attach only (#48) —
     * see {@link dev.locklane.engine.persistence.WorktreeSessionRepository#recordAttach}.
     */
    public PtySession attach(String sessionId, Path workingDirectory, String[] launchCommand, String ownerUsername) {
        return attach(sessionId, workingDirectory, launchCommand, ownerUsername, null, null);
    }

    /**
     * As above, but a brand-new session's PTY starts at the given size instead of
     * pty4j's own default — {@code columns}/{@code rows} are only consulted the first
     * time a session is seen; a reattach reaches the process already running, at
     * whatever size it already is (a client that cares sends a resize once attached).
     */
    public PtySession attach(String sessionId, Path workingDirectory, String[] launchCommand, String ownerUsername,
            Integer columns, Integer rows) {
        String[] command = launchCommand != null ? launchCommand : shellCommand;
        int initialColumns = columns != null ? columns : DEFAULT_COLUMNS;
        int initialRows = rows != null ? rows : DEFAULT_ROWS;
        PtySession session = sessions.computeIfAbsent(sessionId, id -> {
            PtySession created = new PtySession(id, workingDirectory, command, System.getenv(), initialColumns, initialRows);
            // Lives for the session's whole lifetime — never unsubscribed, unlike a
            // browser's own subscription in TerminalWebSocketHandler, which comes and
            // goes with that one connection.
            created.subscribeAttention(state -> eventBroadcaster.broadcast("consoleAttention",
                    Map.of("sessionId", id, "state", state == PtySession.AttentionState.WAITING ? "waiting" : "active")));
            return created;
        });
        repository.recordAttach(sessionId, workingDirectory, Instant.now(), ownerUsername);
        return session;
    }

    /** Re-checks every live session's quiescence fallback (#130). */
    @Scheduled(fixedDelay = QUIESCENCE_POLL_MS)
    void checkQuiescence() {
        sessions.values().forEach(PtySession::checkQuiescence);
    }

    /** Attaches with the default shell and no recorded owner. */
    public PtySession attach(String sessionId, Path workingDirectory) {
        return attach(sessionId, workingDirectory, null, null);
    }

    /** Attaches with no recorded owner. */
    public PtySession attach(String sessionId, Path workingDirectory, String[] launchCommand) {
        return attach(sessionId, workingDirectory, launchCommand, null);
    }

    public Optional<PtySession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * The working directory last recorded for a session, even when this process has
     * no live session for it right now — the case right after a restart, before
     * anyone has reattached.
     */
    public Optional<Path> lastKnownWorkingDirectory(String sessionId) {
        return repository.find(sessionId).map(WorktreeSessionRecord::workingDirectory);
    }

    /**
     * The session's recorded owner, or empty when it has none — no session with
     * this id exists yet, or it was created before per-user ownership existed / by
     * an unauthenticated attach (#48). Either way, treated as unclaimed.
     */
    public Optional<String> ownerUsername(String sessionId) {
        return repository.find(sessionId).map(WorktreeSessionRecord::ownerUsername);
    }

    private static String[] defaultShellCommand() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank() || !Files.isExecutable(Path.of(shell))) {
            shell = Files.isExecutable(Path.of("/bin/bash")) ? "/bin/bash" : "/bin/sh";
        }
        return new String[] {shell, "-i"};
    }

    /**
     * Explicitly closes one session (#75) — unlike a client disconnecting, this ends
     * the process for good and forgets the session's durable record, so it will not
     * reappear on a later list or reattach. A no-op for an id that names no live or
     * recorded session.
     */
    public void close(String sessionId) {
        PtySession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
        }
        repository.delete(sessionId);
    }

    /** Stops every running session. Orphan processes are not left behind on shutdown. */
    @PreDestroy
    void closeAll() {
        sessions.values().forEach(PtySession::close);
        sessions.clear();
    }
}
