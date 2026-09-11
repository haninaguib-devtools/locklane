package dev.locklane.engine.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * One long-lived pseudo-terminal process for a single session, independent of any
 * client connection. A session's identity is its own — separate from whatever
 * working directory (main checkout or worktree) it happens to run in, so more than
 * one session can point at the same directory — and it runs whatever launch command
 * it was started with (a plain shell, or an agent CLI such as {@code claude} or
 * {@code codex}). A background thread drains its output into an in-memory buffer
 * continuously — regardless of whether a client is currently attached — so the
 * process never blocks on a full pipe, and a client that reattaches later sees
 * everything produced while it was gone. Live output is also pushed to any
 * currently-{@link #subscribe subscribed} listener, which is how a network transport
 * (e.g. WebSocket, #7) streams it to a browser in real time.
 */
public final class PtySession {

    private static final Logger log = LoggerFactory.getLogger(PtySession.class);

    // Fills gaps only (#63) — an explicit TERM/COLORTERM the caller's environment
    // already carries (e.g. the engine itself launched from a real terminal) is left
    // alone; this only rescues the common case where it is launched some other way
    // (an IDE run configuration, systemd, ...) and inherits neither at all, which
    // otherwise leaves every CLI running inside a session assuming no color support.
    private static final Map<String, String> DEFAULT_TERMINAL_ENV =
            Map.of("TERM", "xterm-256color", "COLORTERM", "truecolor");

    // #130: a BEL byte is the agent-agnostic "I'm done/waiting" signal Claude Code and
    // similar CLIs ring on completion.
    private static final byte BEL = 0x07;

    // #233: a BEL is also how an OSC escape sequence (`ESC ] ... BEL`) terminates —
    // e.g. the window-title convention Debian/Ubuntu's default interactive `bashrc`
    // emits as part of every prompt. That BEL is punctuation for the sequence, not a
    // real attention signal, so the scan below tracks whether it is currently inside
    // one and never treats its terminating BEL as attention-worthy.
    private static final byte ESC = 0x1B;
    private static final byte OSC_START = ']';
    private static final byte ST_TERMINATOR = '\\';

    private enum BelScanState {
        NORMAL, ESCAPE, OSC, OSC_ESCAPE
    }

    // #861: the largest OSC payload kept for notification parsing — a notification
    // is a short human message; anything larger (an image, a long title) is ignored
    // rather than buffered without bound.
    static final int OSC_PAYLOAD_MAX_BYTES = 4096;
    // #861: the longest notification body kept — agent-controlled text, single line.
    static final int NOTIFICATION_MESSAGE_MAX_CHARS = 200;

    // #130: the quiescence fallback's fixed delay — agents that never ring the bell
    // still go quiet once they finish, so output going silent this long with no input
    // since is itself treated as "waiting for attention". Package-visible so tests can
    // compute an equivalent `nowMs` without a real sleep.
    static final long QUIESCENCE_THRESHOLD_MS = 3000;

    private final String sessionId;
    private final PtyProcess process;
    private final OutputBuffer output = new OutputBuffer();
    private final Set<OutputListener> listeners = ConcurrentHashMap.newKeySet();
    private final Set<AttentionListener> attentionListeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Attention> attention = new AtomicReference<>(Attention.ACTIVE);
    private volatile long lastOutputAt;
    private volatile long lastInputAt;
    // #233: only ever touched from this session's own drain thread, so the scan can
    // carry its state across chunk boundaries with no synchronization.
    private BelScanState belScanState = BelScanState.NORMAL;
    // #861: the current OSC sequence's payload bytes, reset on entering OSC — only
    // ever touched from the drain thread, like belScanState above.
    private final ByteArrayOutputStream oscPayload = new ByteArrayOutputStream();
    private boolean oscTooLong = false;
    // #862: on for a plain shell or an agent whose bell hook is not (yet) wired --
    // exactly today's behaviour; off once the launch command carried an injected bell
    // hook (TerminalWebSocketHandler is the one place that knows), so quiescence adds
    // no false positives for a session that already has a precise signal.
    private final boolean quiescenceFallbackEnabled;

    /** As the full constructor below, with the quiescence fallback on -- today's behaviour. */
    PtySession(String sessionId, Path workingDirectory, String[] command, Map<String, String> environment,
            int initialColumns, int initialRows) {
        this(sessionId, workingDirectory, command, environment, initialColumns, initialRows, true);
    }

    PtySession(String sessionId, Path workingDirectory, String[] command, Map<String, String> environment,
            int initialColumns, int initialRows, boolean quiescenceFallbackEnabled) {
        this.sessionId = sessionId;
        this.quiescenceFallbackEnabled = quiescenceFallbackEnabled;
        try {
            this.process = new PtyProcessBuilder()
                    .setCommand(command)
                    .setDirectory(workingDirectory.toString())
                    .setEnvironment(withDefaultTerminalEnv(environment))
                    .setInitialColumns(initialColumns)
                    .setInitialRows(initialRows)
                    .start();
        } catch (IOException e) {
            throw new PtySessionStartException(sessionId, e);
        }
        long now = System.currentTimeMillis();
        this.lastOutputAt = now;
        this.lastInputAt = now;
        Thread drainThread = new Thread(this::drain, "pty-drain-" + sessionId);
        drainThread.setDaemon(true);
        drainThread.start();
    }

    private void drain() {
        InputStream in = process.getInputStream();
        byte[] chunk = new byte[4096];
        try {
            int n;
            while ((n = in.read(chunk)) != -1) {
                output.append(chunk, n);
                lastOutputAt = System.currentTimeMillis();
                scanChunk(chunk, n);
                if (!listeners.isEmpty()) {
                    // Defensive copy: `chunk` is reused on the next loop iteration, so a
                    // listener that hands this off asynchronously must not see it mutate.
                    byte[] copy = Arrays.copyOf(chunk, n);
                    for (OutputListener listener : listeners) {
                        listener.onOutput(copy);
                    }
                }
            }
        } catch (IOException ignored) {
            // silent: the process ended or its pty closed; nothing more to drain.
        } catch (RuntimeException e) {
            // A listener or scan bug must not silently kill this session's drain
            // thread — the process would keep running with nobody reading its output.
            log.error("Session {}'s drain loop failed", sessionId, e);
        }
    }

    /**
     * Scans one chunk for attention signals (#130, #233, #861): a bare BEL marks
     * waiting, an OSC 9 notification or OSC 777 notify marks waiting with its
     * message, and any other OSC (a title, a color, a progress bar) is ignored. The
     * scan is stateful across calls ({@link #belScanState}, {@link #oscPayload})
     * because a sequence can straddle two {@code read()} chunks. Marks are applied
     * in byte order, so a bell just before or after its OSC in the same chunk still
     * pairs with it — see {@link #markBell} and {@link #markNotification}.
     */
    private void scanChunk(byte[] chunk, int length) {
        for (int i = 0; i < length; i++) {
            byte b = chunk[i];
            switch (belScanState) {
                case NORMAL:
                    if (b == ESC) {
                        belScanState = BelScanState.ESCAPE;
                    } else if (b == BEL) {
                        markBell();
                    }
                    break;
                case ESCAPE:
                    if (b == OSC_START) {
                        belScanState = BelScanState.OSC;
                        oscPayload.reset();
                        oscTooLong = false;
                    } else {
                        belScanState = BelScanState.NORMAL;
                    }
                    break;
                case OSC:
                    if (b == BEL) {
                        // Terminates the sequence — punctuation, not attention (#233).
                        finishOsc();
                        belScanState = BelScanState.NORMAL;
                    } else if (b == ESC) {
                        belScanState = BelScanState.OSC_ESCAPE;
                    } else {
                        appendOscByte(b);
                    }
                    break;
                case OSC_ESCAPE:
                    // ST (ESC \) also terminates an OSC sequence, with no BEL at all.
                    if (b == ST_TERMINATOR) {
                        finishOsc();
                        belScanState = BelScanState.NORMAL;
                    } else {
                        // Not a terminator after all: the ESC belonged to the payload.
                        appendOscByte(ESC);
                        appendOscByte(b);
                        belScanState = BelScanState.OSC;
                    }
                    break;
            }
        }
    }

    private void appendOscByte(byte b) {
        if (oscTooLong) {
            return;
        }
        if (oscPayload.size() >= OSC_PAYLOAD_MAX_BYTES) {
            oscTooLong = true;
            return;
        }
        oscPayload.write(b);
    }

    /** Processes one terminated OSC payload (#861) — ignored unless it is a notification. */
    private void finishOsc() {
        if (oscTooLong) {
            oscPayload.reset();
            oscTooLong = false;
            return;
        }
        String payload = oscPayload.toString(StandardCharsets.UTF_8);
        oscPayload.reset();
        // null: not a notification OSC — a title, a color, progress, anything else.
        // Empty: a notification with no usable message — still waiting, no new body.
        String message = notificationMessageFromOscPayload(payload);
        if (message == null) {
            return;
        }
        if (message.isEmpty()) {
            markBell();
        } else {
            markNotification(message);
        }
    }

    /**
     * The notification message an OSC payload carries (#861), or {@code null} when
     * the payload is not a notification at all. Package-visible for tests.
     *
     * <p>Understood, generically — never per-agent: {@code 9;<message>} (iTerm2's
     * notification, excluding the {@code 9;4...} progress form) and {@code
     * 777;notify;<title>;<body>} (foot's notification, body preferred over title).
     * The returned message is already sanitized, or empty when the notification
     * carried nothing usable.
     */
    static String notificationMessageFromOscPayload(String payload) {
        if (payload.startsWith("9;")) {
            String content = payload.substring(2);
            if (content.equals("4") || content.startsWith("4;")) {
                return null;
            }
            String sanitized = sanitizeNotificationMessage(content);
            return sanitized == null ? "" : sanitized;
        }
        if (payload.startsWith("777;")) {
            String[] parts = payload.split(";", -1);
            if (parts.length < 3 || !"notify".equals(parts[1])) {
                return null;
            }
            String title = parts[2];
            String body = parts.length > 3 ? String.join(";", java.util.Arrays.copyOfRange(parts, 3, parts.length)) : "";
            String chosen = !body.isBlank() ? body : title;
            String sanitized = sanitizeNotificationMessage(chosen);
            return sanitized == null ? "" : sanitized;
        }
        return null;
    }

    /**
     * Trims agent-controlled notification text to a single safe line (#861):
     * control characters become spaces, whitespace collapses, capped at {@link
     * #NOTIFICATION_MESSAGE_MAX_CHARS}. Null when nothing usable remains.
     * Package-visible for tests.
     */
    static String sanitizeNotificationMessage(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // C0/C1 controls (including DEL) carry no message — a space keeps word
            // boundaries where a newline was.
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                cleaned.append(' ');
            } else {
                cleaned.append(c);
            }
        }
        String collapsed = cleaned.toString().replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        if (collapsed.length() > NOTIFICATION_MESSAGE_MAX_CHARS) {
            return collapsed.substring(0, NOTIFICATION_MESSAGE_MAX_CHARS).trim();
        }
        return collapsed;
    }

    public String sessionId() {
        return sessionId;
    }

    /** Everything the session has produced so far, from the start of the buffer. */
    public String bufferedOutput() {
        return output.snapshot();
    }

    public void write(String input) {
        OutputStream stdin = process.getOutputStream();
        try {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            lastInputAt = System.currentTimeMillis();
            markActive();
        } catch (IOException e) {
            throw new PtySessionIoException(sessionId, e);
        }
    }

    /**
     * Clears attention the same way real input does, without writing anything to the
     * process itself — for a client that focuses this session's terminal tab (#130)
     * rather than typing into it.
     */
    public void markFocused() {
        lastInputAt = System.currentTimeMillis();
        markActive();
    }

    /**
     * Registers a listener for output produced from now on (past output is available
     * via {@link #bufferedOutput()}). Returns a handle whose {@code close()}
     * unsubscribes — callers must call it when they stop listening, or the listener
     * (and whatever it holds) leaks for the session's lifetime.
     */
    public AutoCloseable subscribe(OutputListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * The OS handle of this session's shell, for ending the whole tree it spawned at
     * shutdown (#678) — empty once the process is gone. pty4j's process is not a
     * {@code java.lang.Process} the JDK can hand a handle for directly, so it goes
     * through the pid.
     */
    Optional<ProcessHandle> processHandle() {
        return ProcessHandle.of(process.pid());
    }

    /** Tells the running process its terminal changed size (SIGWINCH on Unix). */
    public void resize(int columns, int rows) {
        process.setWinSize(new WinSize(columns, rows));
    }

    private static Map<String, String> withDefaultTerminalEnv(Map<String, String> environment) {
        Map<String, String> merged = new HashMap<>(environment);
        DEFAULT_TERMINAL_ENV.forEach(merged::putIfAbsent);
        return merged;
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            process.destroy();
        }
    }

    /**
     * Registers a listener for this session's attention state (#130) — called
     * immediately on every change, starting from the next one (the current state at
     * subscribe time is not replayed). Returns a handle whose {@code close()}
     * unsubscribes, same contract as {@link #subscribe}.
     */
    public AutoCloseable subscribeAttention(AttentionListener listener) {
        attentionListeners.add(listener);
        return () -> attentionListeners.remove(listener);
    }

    /**
     * This session's attention state right now (#790). {@link #subscribeAttention}
     * never replays the current state, so this is how a client that connects to the
     * events channel after the last change catches up — {@code SessionRegistry}
     * reads it to build the snapshot a new connection is sent.
     */
    public AttentionState attentionState() {
        return attention.get().state();
    }

    /**
     * Why this session is waiting (#854) — {@code null} whenever {@link
     * #attentionState()} is {@link AttentionState#ACTIVE}. Read the same way {@link
     * #attentionState()} is: {@code SessionRegistry} uses it to build the connect-time
     * snapshot too.
     */
    public WaitingReason waitingReason() {
        return attention.get().reason();
    }

    /**
     * The agent's own notification message (#861) — {@code null} whenever {@link
     * #attentionState()} is {@link AttentionState#ACTIVE} or no OSC notification has
     * been seen since. Read the same way {@link #waitingReason()} is.
     */
    public String waitingMessage() {
        return attention.get().message();
    }

    /**
     * Re-evaluates the quiescence fallback (#130): output that has gone quiet for
     * {@link #QUIESCENCE_THRESHOLD_MS} with no input sent since marks the session as
     * waiting, for an agent that never rings the bell. Never called on a timer inside
     * this class — a session has no thread of its own idle-ticking; {@link
     * SessionRegistry} polls every live session on a schedule.
     */
    void checkQuiescence() {
        checkQuiescence(System.currentTimeMillis());
    }

    /** As above, with an explicit "now" so a test can evaluate this with no real sleep. */
    void checkQuiescence(long nowMs) {
        if (!quiescenceFallbackEnabled) {
            return;
        }
        if (nowMs - lastOutputAt >= QUIESCENCE_THRESHOLD_MS && lastInputAt <= lastOutputAt) {
            markQuiet();
        }
    }

    /** A BEL was seen (#854): the strongest reason, so it always wins over quiet. */
    private void markBell() {
        updateAttention(current -> new Attention(AttentionState.WAITING, WaitingReason.BELL, current.message()));
    }

    /**
     * An OSC notification was seen (#861): waiting with reason bell, carrying its
     * message — the same strength as a bare bell, so it wins over quiet and
     * re-emits when the message differs from one already recorded.
     */
    private void markNotification(String message) {
        updateAttention(current -> new Attention(AttentionState.WAITING, WaitingReason.BELL, message));
    }

    /**
     * Output has gone quiet (#854): marks waiting, unless a bell already did — a bell
     * followed by quiet must keep reporting {@code bell}, never downgrade it.
     */
    private void markQuiet() {
        updateAttention(current -> current.state() == AttentionState.WAITING && current.reason() == WaitingReason.BELL
                ? current
                : new Attention(AttentionState.WAITING, WaitingReason.QUIET, null));
    }

    private void markActive() {
        updateAttention(current -> Attention.ACTIVE);
    }

    /**
     * Applies {@code transition} atomically and notifies every {@link
     * AttentionListener} exactly when the result actually differs from before — so a
     * session already waiting for quiet that then rings the bell re-emits with the
     * stronger reason even though {@link AttentionState} itself did not change (#854),
     * and a second notification with a different message re-emits too (#861).
     */
    private void updateAttention(UnaryOperator<Attention> transition) {
        Attention previous;
        Attention updated;
        do {
            previous = attention.get();
            updated = transition.apply(previous);
        } while (!attention.compareAndSet(previous, updated));
        if (!previous.equals(updated)) {
            for (AttentionListener listener : attentionListeners) {
                listener.onAttentionChange(updated.state(), updated.reason(), updated.message());
            }
        }
    }

    @FunctionalInterface
    public interface OutputListener {
        void onOutput(byte[] chunk);
    }

    /** Whether a session looks like it's waiting on the user, or the user is on top of it. */
    public enum AttentionState {
        WAITING,
        ACTIVE
    }

    /**
     * Why a session is {@link AttentionState#WAITING} (#854): a deliberate {@code
     * bell}, the agent-agnostic completion signal, or the {@code quiet} fallback for
     * an agent that never rings it. Never present alongside {@link
     * AttentionState#ACTIVE}.
     */
    public enum WaitingReason {
        BELL,
        QUIET;

        /** This reason's wire value, e.g. in the {@code consoleAttention} event. */
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** This session's state and, when waiting, why — see {@link #updateAttention}. */
    private record Attention(AttentionState state, WaitingReason reason, String message) {
        static final Attention ACTIVE = new Attention(AttentionState.ACTIVE, null, null);
    }

    @FunctionalInterface
    public interface AttentionListener {
        void onAttentionChange(AttentionState state, WaitingReason reason, String message);
    }
}
