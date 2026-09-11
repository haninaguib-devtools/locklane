package dev.locklane.engine.pty;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.AgentSessionResumeSessionRecord;
import dev.locklane.engine.persistence.AgentSessionResumeSessionRepository;
import dev.locklane.engine.persistence.WorktreeSessionRecord;
import dev.locklane.engine.persistence.WorktreeSessionRepository;
import dev.locklane.engine.process.ProcessTrees;
import dev.locklane.engine.uploads.SessionUploadStorage;
import dev.locklane.engine.ws.EventBroadcaster;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
 * via {@link AgentSessionResumeSessionRepository} (#102). An agent session genuinely opening or
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

    // Every real agent session id is shaped "<projectId>-..." (#43); a test fixture id
    // that isn't just broadcasts with no projectId field rather than failing (#195).
    private static final Pattern PROJECT_ID_PREFIX = Pattern.compile("^(\\d+)-");

    private final Map<String, PtySession> sessions = new ConcurrentHashMap<>();
    private final String[] shellCommand;
    private final WorktreeSessionRepository repository;
    private final AgentSessionResumeSessionRepository resumeRepository;
    private final EventBroadcaster eventBroadcaster;
    // Nullable (test-only constructors): files uploaded onto this session's terminal
    // (#436) — removed when the session ends for good in close() below, and only
    // there: a disconnect or engine restart keeps the session's record, so it keeps
    // its uploads too.
    private final SessionUploadStorage uploadStorage;
    // Session-scoped resources outside this class (code-server's process, #628) that
    // need to end when a session does, without SessionRegistry needing to know what
    // kind of resource each one is — the same role uploadStorage above plays for
    // uploads, generalized so a future resource doesn't need its own field and wiring
    // here.
    private final List<Consumer<String>> closeListeners = new CopyOnWriteArrayList<>();
    // Every session's attention transitions (#130, #854), for a consumer outside this
    // class that acts on them (Web Push, #860) -- the same funnel shape as
    // closeListeners: registered once, consulted live at event time, so a listener
    // added after a session was created still hears that session.
    private final List<AttentionListener> attentionListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public SessionRegistry(WorktreeSessionRepository repository, AgentSessionResumeSessionRepository resumeRepository,
            EventBroadcaster eventBroadcaster, SessionUploadStorage uploadStorage) {
        this.repository = repository;
        this.resumeRepository = resumeRepository;
        this.eventBroadcaster = eventBroadcaster;
        this.uploadStorage = uploadStorage;
        this.shellCommand = defaultShellCommand();
    }

    /**
     * Test-only: a broadcaster with no registered sessions, since most tests here
     * don't care about the events channel (#130's own attention tests subscribe on
     * the {@link PtySession} directly instead), and no resume-id capture (#102) —
     * a null repository turns the scan off; tests that care pass a real one.
     */
    public SessionRegistry(WorktreeSessionRepository repository) {
        this(repository, null, new EventBroadcaster(new ObjectMapper()), null);
    }

    /** Test-only: resume-id capture on (#102), events channel off — see above. */
    public SessionRegistry(WorktreeSessionRepository repository, AgentSessionResumeSessionRepository resumeRepository) {
        this(repository, resumeRepository, new EventBroadcaster(new ObjectMapper()), null);
    }

    /** Test-only: a real events channel, no resume capture and no upload cleanup (#436). */
    public SessionRegistry(WorktreeSessionRepository repository, AgentSessionResumeSessionRepository resumeRepository,
            EventBroadcaster eventBroadcaster) {
        this(repository, resumeRepository, eventBroadcaster, null);
    }

    /**
     * Returns the session's running process, starting one if none exists yet. A
     * {@code null} launch command falls back to the default shell — the launch
     * command only matters for a session's first attach; a reattach reaches the
     * process already running, whatever it was started with. {@code ownerUsername}
     * (nullable) is stamped on a first attach only — see {@link
     * dev.locklane.engine.persistence.WorktreeSessionRepository#recordAttach} — but is
     * purely informational since #242: who may attach at all is decided upstream
     * ({@code TerminalWebSocketHandler}, against the session's owning project), not
     * by this column.
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
     * merged over the host's own environment (#139 — a project-level agent session's
     * {@code GH_TOKEN}) — like {@code launchCommand}/{@code columns}/{@code rows},
     * consulted only the first time a session is seen; a reattach reaches the
     * process already running, with whatever environment it already started with.
     */
    public PtySession attach(String sessionId, Path workingDirectory, String[] launchCommand, String ownerUsername,
            Integer columns, Integer rows, Map<String, String> extraEnvironment) {
        return attach(sessionId, workingDirectory, launchCommand, ownerUsername, columns, rows, extraEnvironment, true);
    }

    /**
     * As above, but a brand-new session's quiescence fallback (#130, #862) is left off
     * when {@code quiescenceFallbackEnabled} is false — for a launch command that
     * already carries an injected bell hook, which is a precise "waiting" signal on its
     * own and makes the fallback pure noise. {@code TerminalWebSocketHandler} is the
     * one caller that knows this at launch-command-composition time; consulted only
     * the first time a session is seen, same as {@code launchCommand} itself — a
     * reattach reaches the process already running, with whatever the flag was set to
     * then.
     */
    public PtySession attach(String sessionId, Path workingDirectory, String[] launchCommand, String ownerUsername,
            Integer columns, Integer rows, Map<String, String> extraEnvironment, boolean quiescenceFallbackEnabled) {
        String[] command = launchCommand != null ? launchCommand : shellCommand;
        int initialColumns = columns != null ? columns : DEFAULT_COLUMNS;
        int initialRows = rows != null ? rows : DEFAULT_ROWS;
        // Checked before recordAttach below turns this into an upsert (#195): a session
        // already has a persisted record whenever any client-visible listing already
        // counts it as open, including a reattach after this process restarted with no
        // live PtySession yet — broadcasting in that case would report a change that
        // never actually happened to the list.
        boolean isNewAgentSession = repository.find(sessionId).isEmpty();
        PtySession session = sessions.computeIfAbsent(sessionId, id -> {
            Map<String, String> environment = mergedEnvironment(extraEnvironment);
            PtySession created = new PtySession(id, workingDirectory, command, environment, initialColumns, initialRows,
                    quiescenceFallbackEnabled);
            // Lives for the session's whole lifetime — never unsubscribed, unlike a
            // browser's own subscription in TerminalWebSocketHandler, which comes and
            // goes with that one connection.
            created.subscribeAttention((state, reason, message) -> {
                eventBroadcaster.broadcast("consoleAttention", attentionFields(id, state, reason, message));
                for (AttentionListener listener : attentionListeners) {
                    try {
                        listener.onAttentionChange(id, state, reason, message);
                    } catch (RuntimeException e) {
                        // Contained (#860): this runs on the session's drain thread, and
                        // a listener's failure must not cost the broadcast or the thread.
                        log.warn("Attention listener failed for session {}", id, e);
                    }
                }
            });
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
        if (isNewAgentSession) {
            broadcastAgentSessionsChanged(sessionId);
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
        try {
            sessions.values().forEach(PtySession::checkQuiescence);
        } catch (RuntimeException e) {
            log.error("Scheduled quiescence check failed", e);
        }
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
     * Every live session currently waiting for attention, with why (#790, #854) —
     * what {@code EventsWebSocketHandler} sends a newly connected client as its
     * {@code consoleAttention} snapshot, so a page opened or reconnected after a
     * session rang the bell shows it as waiting, with the same reason, without the
     * live broadcast having to fire again. Live sessions only: a session with a
     * persisted record but no process in this engine (the case right after a restart)
     * has no attention state to report. The order is whatever the registry's map
     * yields; nothing depends on it.
     */
    public List<WaitingSession> waitingSessions() {
        return sessions.entrySet().stream()
                .filter(entry -> entry.getValue().attentionState() == PtySession.AttentionState.WAITING)
                .map(entry -> new WaitingSession(entry.getKey(), entry.getValue().waitingReason(),
                        entry.getValue().waitingMessage()))
                .toList();
    }

    /** One live session's id, why it is waiting (#854), and the agent's own message when one was seen (#861). */
    public record WaitingSession(String sessionId, PtySession.WaitingReason reason, String message) {
    }

    /**
     * The {@code consoleAttention} event's fields (#854, #861): {@code reason} is
     * present only alongside {@code state: "waiting"}, matching {@link PtySession}'s
     * own contract that a {@link PtySession.WaitingReason} never accompanies {@code
     * ACTIVE} — an active event stays exactly the shape it always was. {@code
     * message} is present only alongside waiting with a notification seen since.
     */
    private static Map<String, Object> attentionFields(String sessionId, PtySession.AttentionState state,
            PtySession.WaitingReason reason, String message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sessionId", sessionId);
        fields.put("state", state == PtySession.AttentionState.WAITING ? "waiting" : "active");
        if (reason != null) {
            fields.put("reason", reason.wireValue());
        }
        if (message != null) {
            fields.put("message", message);
        }
        return fields;
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
     * {@link AgentSessionResumeSessionRepository}.
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
                .map(AgentSessionResumeSessionRecord::resumeId);
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
        // Files uploaded onto this session's terminal (#436) end with it —
        // unconditionally, not only when something was open: a folder can survive
        // from before an engine restart, when the live session was already gone.
        if (uploadStorage != null) {
            uploadStorage.deleteFor(sessionId);
        }
        // Same unconditional reasoning as uploadStorage above (#628): a registered
        // resource (code-server's process) is asked to end for this id regardless of
        // wasOpen, so it's never left running past a session it's tied to.
        closeListeners.forEach(listener -> listener.accept(sessionId));
        if (wasOpen) {
            broadcastAgentSessionsChanged(sessionId);
        }
    }

    /**
     * Registers a listener invoked with a session's id on every {@link #close}, so a
     * session-scoped resource elsewhere (code-server's process, #628) can end when its
     * agent session does without this class needing to know what kind of resource it is.
     */
    public void addCloseListener(Consumer<String> listener) {
        closeListeners.add(listener);
    }

    /**
     * Registers a listener invoked with a session's id on every attention transition
     * of every session (#860) -- the same {@code (state, reason, message)} triple
     * {@code consoleAttention} broadcasts, for a consumer that needs to act on it
     * server-side. Called on the session's own output-drain thread: a listener hands
     * anything slow elsewhere.
     */
    public void addAttentionListener(AttentionListener listener) {
        attentionListeners.add(listener);
    }

    /** A session-scoped attention transition, with the session it belongs to -- see {@link #addAttentionListener}. */
    @FunctionalInterface
    public interface AttentionListener {
        void onAttentionChange(String sessionId, PtySession.AttentionState state, PtySession.WaitingReason reason,
                String message);
    }

    /**
     * Tells every connected browser a project's open-agent-session list may have changed
     * (#195), over the same app-wide channel {@code consoleAttention}/
     * {@code issuesChanged} already use — so a header widget watching this project
     * in another tab knows to re-fetch instead of going stale until a manual reload.
     */
    private void broadcastAgentSessionsChanged(String sessionId) {
        Matcher matcher = PROJECT_ID_PREFIX.matcher(sessionId);
        if (matcher.find()) {
            eventBroadcaster.broadcast("consolesChanged", Map.of("projectId", Long.parseLong(matcher.group(1))));
        } else {
            eventBroadcaster.broadcast("consolesChanged");
        }
    }

    /**
     * Stops every running session at shutdown — the shells and everything they spawned
     * (#678). The trees are collected and ended <em>before</em> the shells are closed:
     * a shell that is destroyed first leaves orphans no longer findable from it, which
     * on macOS (no control group to sweep them) means an agent or a build still
     * running after the engine is gone.
     */
    @PreDestroy
    void closeAll() {
        List<ProcessHandle> shells = sessions.values().stream()
                .map(PtySession::processHandle)
                .flatMap(Optional::stream)
                .toList();
        List<ProcessHandle> left = ProcessTrees.terminate(shells, SHUTDOWN_GRACE);
        if (!left.isEmpty()) {
            log.warn("session processes still alive after shutdown: {}",
                    left.stream().map(ProcessHandle::pid).toList());
        }
        sessions.values().forEach(PtySession::close);
        sessions.clear();
    }

    /** How long shutdown waits for session process trees to exit before killing them. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);
}
