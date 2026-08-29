package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ProjectConsoleService;
import dev.locklane.engine.pty.PtySession;
import dev.locklane.engine.pty.SessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Attaches a browser client to a session's {@link PtySession} over WebSocket:
 * {@code /ws/sessions/{sessionId}[?dir=<path>][&cmd=<claude|codex|shell>][&resume=<id>][&cols=<n>&rows=<n>]}.
 * {@code dir} is required only the first time a session is seen; after that its working
 * directory is already known (in-memory if the session is still live, or from SQLite via
 * {@link SessionRegistry#lastKnownWorkingDirectory} after a restart). {@code cmd}
 * chooses what a brand-new session launches — an agent CLI (e.g. {@code claude},
 * {@code codex}, {@code opencode}) or a plain shell (the default, when {@code cmd} is
 * absent or {@code shell}) — and is ignored on a reattach to an already-running session.
 * {@code resume} (#103, #295) makes a brand-new {@code claude}/{@code codex}/
 * {@code opencode} session resume a past conversation instead of starting a blank one
 * ({@code claude --resume <id>} / {@code codex resume <id>} / {@code opencode --session
 * <id>}, the ids captured by #102); the command is composed here, never accepted as a
 * free-form string, and {@code resume} is ignored for any other {@code cmd} or an id not
 * shaped like one. Reattaching to a {@code claude}/{@code codex}/{@code opencode}
 * session whose process did not survive an engine restart resumes on its own (#173):
 * with no explicit {@code resume} and no live process, the most recently captured resume
 * id for that session and tool fills in automatically.
 * {@code cols}/{@code rows} size a brand-new session's PTY to the browser terminal's
 * actual size instead of a hardcoded default (#62); once attached, later size changes
 * arrive as resize messages (see below), not new query parameters. A brand-new
 * session also gets whatever extra environment {@link ProjectConsoleService}
 * resolves for its id (#139) — {@code GH_TOKEN} for a project console, nothing for
 * any other session — merged in before the process starts.
 *
 * <p>Closing a connection never kills the underlying session (#7's done-when) — only
 * this connection's subscription is torn down, so the session keeps running and
 * producing output for the next client to reattach and replay.
 *
 * <p>An inbound text message carries a one-character type tag the client always
 * prepends (#62) — {@code '0'} for keystroke input, {@code '1'} for a resize, {@code
 * '2'} for a focus notification (#130, carries no body) — so a keystroke's own bytes
 * are never mistaken for the tag: the client wraps every message it sends rather than
 * ever forwarding raw terminal bytes on their own.
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final char INPUT = '0';
    private static final char RESIZE = '1';
    private static final char FOCUS = '2';

    private final SessionRegistry sessionRegistry;
    private final ProjectConsoleService projectConsoleService;
    private final TerminalHeartbeat heartbeat;
    private final Map<String, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

    @Autowired
    public TerminalWebSocketHandler(SessionRegistry sessionRegistry, ProjectConsoleService projectConsoleService,
            Clock clock, @Value("${locklane.terminal.heartbeat-interval-ms}") long heartbeatIntervalMs) {
        this.sessionRegistry = sessionRegistry;
        this.projectConsoleService = projectConsoleService;
        this.heartbeat = new TerminalHeartbeat(clock, heartbeatIntervalMs);
    }

    /** Test-only: these tests never call {@link #afterConnectionEstablished}, so the heartbeat is never exercised. */
    public TerminalWebSocketHandler(SessionRegistry sessionRegistry, ProjectConsoleService projectConsoleService) {
        this(sessionRegistry, projectConsoleService, Clock.systemUTC(), 20_000L);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        String sessionId = sessionId(wsSession);
        Path workingDirectory = resolveWorkingDirectory(wsSession, sessionId);
        if (workingDirectory == null) {
            wsSession.close(CloseStatus.BAD_DATA.withReason(
                    "Unknown session '" + sessionId + "': pass ?dir=<path> to start one"));
            return;
        }

        // Authentication itself is enforced upstream (SecurityConfig, #50) — a
        // handshake reaches here only once Spring Security has already accepted a
        // session cookie, so getPrincipal() is never null in practice. The null
        // check is defensive, not load-bearing: this is ownership (#48), not auth.
        String username = wsSession.getPrincipal() != null ? wsSession.getPrincipal().getName() : null;
        Optional<String> owner = sessionRegistry.ownerUsername(sessionId);
        if (owner.isPresent() && !owner.get().equals(username)) {
            wsSession.close(CloseStatus.POLICY_VIOLATION.withReason("This session belongs to another user"));
            return;
        }

        String[] launchCommand = resolveLaunchCommand(sessionId, queryParam(wsSession, "cmd"),
                queryParam(wsSession, "resume"));
        Integer columns = parseIntParam(wsSession, "cols");
        Integer rows = parseIntParam(wsSession, "rows");
        // Empty for anything that isn't a project console's session id (#139) — a
        // no-op merge for every ordinary worktree/main-checkout session.
        Map<String, String> extraEnvironment = projectConsoleService.environmentFor(sessionId);
        PtySession session = sessionRegistry.attach(sessionId, workingDirectory, launchCommand, username, columns,
                rows, extraEnvironment);

        // Replay everything produced so far before subscribing, so nothing produced
        // between the snapshot and the subscription taking effect is lost or
        // duplicated — subscribe() only ever delivers output from this point on.
        wsSession.sendMessage(new TextMessage(session.bufferedOutput()));
        AutoCloseable subscription = session.subscribe(chunk -> forward(wsSession, chunk));
        subscriptions.put(wsSession.getId(), subscription);
        heartbeat.track(wsSession);
    }

    @Override
    protected void handlePongMessage(WebSocketSession wsSession, PongMessage message) {
        heartbeat.recordPong(wsSession);
    }

    /**
     * Detects a stale/half-open connection within a bounded time (#279) — see
     * {@link TerminalHeartbeat}. The interval is configurable
     * ({@code locklane.terminal.heartbeat-interval-ms}) so a test can run this on a
     * much shorter cycle than production without changing the code.
     */
    @Scheduled(fixedDelayString = "${locklane.terminal.heartbeat-interval-ms}")
    void sendHeartbeats() {
        heartbeat.tick();
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) {
        String payload = message.getPayload();
        if (payload.isEmpty()) {
            return;
        }
        char type = payload.charAt(0);
        String body = payload.substring(1);
        String sessionId = sessionId(wsSession);
        sessionRegistry.find(sessionId).ifPresent(session -> {
            if (type == INPUT) {
                session.write(body);
            } else if (type == RESIZE) {
                resize(session, body);
            } else if (type == FOCUS) {
                session.markFocused();
            }
        });
    }

    /** {@code body} is {@code "<columns>x<rows>"} (e.g. {@code "120x40"}); malformed is ignored. */
    private static void resize(PtySession session, String body) {
        int separator = body.indexOf('x');
        if (separator < 0) {
            return;
        }
        try {
            int columns = Integer.parseInt(body.substring(0, separator));
            int rows = Integer.parseInt(body.substring(separator + 1));
            session.resize(columns, rows);
        } catch (NumberFormatException ignored) {
            // Not a resize this handler can act on; nothing productive to do with it.
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) throws Exception {
        AutoCloseable subscription = subscriptions.remove(wsSession.getId());
        if (subscription != null) {
            subscription.close();
        }
        heartbeat.untrack(wsSession);
        // No call into SessionRegistry/PtySession here, deliberately: this connection
        // closing must never stop the session itself.
    }

    private static void forward(WebSocketSession wsSession, byte[] chunk) {
        if (!wsSession.isOpen()) {
            return;
        }
        try {
            wsSession.sendMessage(new TextMessage(new String(chunk, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            // The connection is going away; afterConnectionClosed will clean up the
            // subscription shortly. Nothing productive to do with this failure here.
        }
    }

    private static String sessionId(WebSocketSession wsSession) {
        String path = wsSession.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private Path resolveWorkingDirectory(WebSocketSession wsSession, String sessionId) {
        String dirParam = queryParam(wsSession, "dir");
        if (dirParam != null) {
            return Path.of(dirParam);
        }
        return sessionRegistry.lastKnownWorkingDirectory(sessionId).orElse(null);
    }

    // The ids #102 captures for claude/codex are UUIDs; #295's opencode ids are
    // ULID-based (`ses_` + 20-32 base32-ish characters) instead. Anything else is not
    // something the resume commands accept, so it is ignored rather than handed to a
    // process.
    private static final Pattern RESUME_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "|ses_[0-9A-Za-z]{20,32}");

    /**
     * As {@link #resolveLaunchCommand(String, String)}, but when the client named no
     * conversation itself, {@code cmd} is a resumable tool, and no live process
     * exists for this session — the state an engine restart leaves every session in
     * (#173) — the most recently captured resume id for this session and tool
     * (#102) fills in, so reattaching picks the conversation back up instead of
     * launching a blank one. A session with nothing captured resolves to the plain
     * command exactly as before; with a live process the launch command is ignored
     * by {@link SessionRegistry#attach} anyway, so the lookup is skipped and a
     * plain reattach stays untouched. Package-visible for tests.
     */
    String[] resolveLaunchCommand(String sessionId, String cmd, String resume) {
        if (resume == null && cmd != null && (cmd.equals("claude") || cmd.equals("codex") || cmd.equals("opencode"))
                && sessionRegistry.find(sessionId).isEmpty()) {
            resume = sessionRegistry.latestResumeId(sessionId, cmd).orElse(null);
        }
        return resolveLaunchCommand(cmd, resume);
    }

    /** {@code null} (absent or "shell") defers to {@link SessionRegistry}'s default shell. Package-visible for tests. */
    static String[] resolveLaunchCommand(String cmd, String resume) {
        if (cmd == null || cmd.isBlank() || cmd.equals("shell")) {
            return null;
        }
        if (resume != null && RESUME_ID.matcher(resume).matches()) {
            if (cmd.equals("claude")) {
                return new String[] {"claude", "--resume", resume};
            }
            if (cmd.equals("codex")) {
                return new String[] {"codex", "resume", resume};
            }
            if (cmd.equals("opencode")) {
                return new String[] {"opencode", "--session", resume};
            }
        }
        return new String[] {cmd};
    }

    private static Integer parseIntParam(WebSocketSession wsSession, String name) {
        String raw = queryParam(wsSession, name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String queryParam(WebSocketSession wsSession, String name) {
        String query = wsSession.getUri().getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (param.substring(0, eq).equals(name)) {
                return URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
