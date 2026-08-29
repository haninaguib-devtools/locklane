package dev.locklane.engine.pty;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.ConsoleResumeSessionRecord;
import dev.locklane.engine.persistence.ConsoleResumeSessionRepository;
import dev.locklane.engine.persistence.WorktreeSessionRecord;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.ws.EventBroadcaster;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds one {@link PtySession} per session id. A session's id is its own —
 * independent of the worktree/working-directory it runs in — so more than one
 * session can point at the same directory (including the main checkout, with no
 * worktree involved at all). Attaching to a session id that already has a running
 * session returns that same session rather than starting a new process. Every
 * attach is also recorded in {@link WorktreeSessionRepository}, so a session's
 * last-known state — which directory it ran in, when it was last attached to —
 * survives a server restart even though the live process does not (#6). Each new
 * session's output is additionally watched for a Claude/Codex resume id, persisted
 * via {@link ConsoleResumeSessionRepository} (#102). A console genuinely opening or
 * closing — not a reattach to one already counted as open — is broadcast on
 * {@link EventBroadcaster} as {@code consolesChanged} (#195), so every browser
 * watching that project hears about it, not only the tab that caused it.
 */
@Service
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    // pty4j's own default when nobody says otherwise — used only for a session's
    // first attach; a browser terminal reports its real size moments later (#62).
    private static final int DEFAULT_COLUMNS = 80;
    private static final int DEFAULT_ROWS = 24;

    // #130: how often every live session's quiescence fallback is re-checked — tight
    // enough that a session which just went quiet is caught within about a second of
    // crossing PtySession.QUIESCENCE_THRESHOLD_MS, not a user-visible extra delay.
    private static final long QUIESCENCE_POLL_MS = 1000;

    // Every real console id is shaped "<projectId>-..." (#43); a test fixture id
    // that isn't just broadcasts with no projectId field rather than failing (#195).
    private static final Pattern PROJECT_ID_PREFIX = Pattern.compile("^(\\d+)-");

    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
    private final String[] shellCommand;
    private final WorktreeSessionRepository repository;
    private final ConsoleResumeSessionRepository resumeRepository;
    private final EventBroadcaster eventBroadcaster;

    @Autowired
    public SessionRegistry(WorktreeSessionRepository repository, ConsoleResumeSessionRepository resumeRepository,
            EventBroadcaster eventBroadcaster) {
        this.repository = repository;
        this.resumeRepository = resumeRepository;
        this.eventBroadcaster = eventBroadcaster;
        this.shellCommand = defaultShellCommand();
    }

    /**
     * Test-only: a broadcaster with no registered sessions, since most tests here
     * don't care about the events channel (#130's own attention tests subscribe on
     * the {@link PtySession} directly instead), and no resume-id capture (#102) —
     * a null repository turns the scan off; tests that care pass a real one.
     */
    public SessionRegistry(WorktreeSessionRepository repository) {
        this(repository, null, new EventBroadcaster(new ObjectMapper()));
    }

    /** Test-only: resume-id capture on (#102), events channel off — see above. */
    public SessionRegistry(WorktreeSessionRepository repository, ConsoleResumeSessionRepository resumeRepository) {
        this(repository, resumeRepository, new EventBroadcaster(new ObjectMapper()));
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
        return attach(sessionId, workingDirectory, launchCommand, ownerUsername, columns, rows, Map.of());
    }

    /**
     * As above, but a brand-new session's process also gets {@code extraEnvironment}
     * merged over the host's own environment (#139 — a project-level console's
     * {@code GH_TOKEN}) — like {@code launchCommand}/{@code columns}/{@code rows},
     * consulted only the first time a session is seen; a reattach reaches the
     * process already running, with whatever environment it already started with.
     */
    public PtySession attach(String sessionId, Path workingDirectory, String[] launchCommand, String ownerUsername,
            Integer columns, Integer rows, Map<String, String> extraEnvironment) {
        String[] command = launchCommand != null ? launchCommand : shellCommand;
        int initialColumns = columns != null ? columns : DEFAULT_COLUMNS;
        int initialRows = rows != null ? rows : DEFAULT_ROWS;
        // Checked before recordAttach below turns this into an upsert (#195): a session
        // already has a persisted record whenever any client-visible listing already
        // counts it as open, including a reattach after this process restarted with no
        // live PtySession yet — broadcasting in that case would report a change that
        // never actually happened to the list.
        boolean isNewConsole = repository.find(sessionId).isEmpty();
        PtySession session = sessions.computeIfAbsent(sessionId, id -> {
            Map<String, String> environment = mergedEnvironment(extraEnvironment);
            PtySession created = new PtySession(id, workingDirectory, command, environment, initialColumns, initialRows);
            // Lives for the session's whole lifetime — never unsubscribed, unlike a
            // browser's own subscription in TerminalWebSocketHandler, which comes and
            // goes with that one connection.
            created.subscribeAttention(state -> eventBroadcaster.broadcast("consoleAttention",
                    Map.of("sessionId", id, "state", state == PtySession.AttentionState.WAITING ? "waiting" : "active")));
            if (resumeRepository != null) {
                // Same lifetime as the attention subscription above: watches the whole
                // stream for a Claude/Codex resume id (#102) and persists each new one.
                // The scanner is only ever touched from this session's drain thread.
                ResumeIdScanner scanner = new ResumeIdScanner(ResumeIdScanner.toolHintFor(command));
                created.subscribe(chunk -> scanner.feed(chunk).forEach(capture -> {
                    try {
                        resumeRepository.record(id, capture.tool(), capture.resumeId(), Instant.now());
                    } catch (RuntimeException e) {
                        // A failed write must never escape into PtySession's drain
                        // loop — that would kill the thread draining this session's
                        // output and stall the process on a full pipe.
                        log.warn("Failed to persist resume id for session {}", id, e);
                    }
                }));
            }
            return created;
        });
        repository.recordAttach(sessionId, workingDirectory, Instant.now(), ownerUsername);
        if (isNewConsole) {
            broadcastConsolesChanged(sessionId);
        }
        return session;
    }

    private static Map<String, String> mergedEnvironment(Map<String, String> extraEnvironment) {
        if (extraEnvironment.isEmpty()) {
            return System.getenv();
        }
        Map<String, String> merged = new HashMap<>(System.getenv());
        merged.putAll(extraEnvironment);
        return merged;
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
     * Whether any currently-live session's working directory is {@code directory}
     * itself or a path inside it (#319's cleanup sweep) — checked by directory, not
     * by session id, because a session's id need not match the worktree's own id: a
     * reopened conversation ({@code WorktreeCreationService#reopenSession}) mints a
     * fresh {@code -resume-} id that can point at the very same directory as the
     * issue's one reusable worktree session. A directory with no live session at all
     * (nothing in {@link #sessions}, or every live session's recorded directory lies
     * elsewhere) is {@code false} — never guessed from disk state.
     */
    public boolean hasLiveSessionIn(Path directory) {
        Path normalized = directory.normalize();
        return sessions.keySet().stream()
                .map(repository::find)
                .flatMap(Optional::stream)
                .map(WorktreeSessionRecord::workingDirectory)
                .map(Path::normalize)
                .anyMatch(sessionDirectory -> sessionDirectory.equals(normalized) || sessionDirectory.startsWith(normalized));
    }

    /**
     * The most recently captured resume id (#102) for this session and tool, or
     * empty when capture is off or nothing was ever captured here for that tool.
     * This is what lets a reattach after an engine restart pick the conversation
     * back up (#173): the live process is gone, but the id it printed survives in
     * {@link ConsoleResumeSessionRepository}.
     */
    public Optional<String> latestResumeId(String sessionId, String tool) {
        if (resumeRepository == null) {
            return Optional.empty();
        }
        // findByWorktree returns oldest sighting first; the last matching row is
        // the conversation the user was most recently in.
        return resumeRepository.findByWorktree(sessionId).stream()
                .filter(record -> record.tool().equals(tool))
                .reduce((older, newer) -> newer)
                .map(ConsoleResumeSessionRecord::resumeId);
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
        // Checked before removal (#195): a genuine no-op — nothing live and nothing
        // persisted — must broadcast nothing, exactly matching this method's own
        // "no-op for an id that names no live or recorded session" contract above.
        boolean wasOpen = sessions.containsKey(sessionId) || repository.find(sessionId).isPresent();
        PtySession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
        }
        repository.delete(sessionId);
        if (wasOpen) {
            broadcastConsolesChanged(sessionId);
        }
    }

    /**
     * Tells every connected browser a project's open-console list may have changed
     * (#195), over the same app-wide channel {@code consoleAttention}/
     * {@code issuesChanged} already use — so a header widget watching this project
     * in another tab knows to re-fetch instead of going stale until a manual reload.
     */
    private void broadcastConsolesChanged(String sessionId) {
        Matcher matcher = PROJECT_ID_PREFIX.matcher(sessionId);
        if (matcher.find()) {
            eventBroadcaster.broadcast("consolesChanged", Map.of("projectId", Long.parseLong(matcher.group(1))));
        } else {
            eventBroadcaster.broadcast("consolesChanged");
        }
    }

    /** Stops every running session. Orphan processes are not left behind on shutdown. */
    @PreDestroy
    void closeAll() {
        sessions.values().forEach(PtySession::close);
        sessions.clear();
    }
}
