package dev.locklane.engine.ws;

import dev.locklane.engine.persistence.ProjectConsoleService;
import dev.locklane.engine.persistence.WorktreeSessionAuthorization;
import dev.locklane.engine.pty.PtySession;
import dev.locklane.engine.pty.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Attaches a browser client to a session's {@link PtySession} over WebSocket:
 * {@code /ws/sessions/{sessionId}[?dir=<path>][&cmd=<claude|codex|opencode|omp|shell>][&resume=<id>][&seed=template][&cols=<n>&rows=<n>]}.
 * {@code dir} is required only the first time a session is seen; after that its working
 * directory is already known (in-memory if the session is still live, or from SQLite via
 * {@link SessionRegistry#lastKnownWorkingDirectory} after a restart). {@code cmd}
 * chooses what a brand-new session launches — an agent CLI (e.g. {@code claude},
 * {@code codex}, {@code opencode}) or a plain shell (the default, when {@code cmd} is
 * absent or {@code shell}) — and is ignored on a reattach to an already-running session.
 * {@code resume} (#103, #295, #681) makes a brand-new {@code claude}/{@code codex}/
 * {@code opencode}/{@code omp} session resume a past conversation instead of starting a blank one
 * ({@code claude --resume <id>} / {@code codex resume <id>} / {@code opencode --session
 * <id>} / {@code omp --resume <id>}, the ids captured by #102, #295, #681); the command is composed here, never accepted as a
 * free-form string, and {@code resume} is ignored for any other {@code cmd} or an id not
 * shaped like one. Reattaching to a {@code claude}/{@code codex}/{@code opencode}/
 * {@code omp} session whose process did not survive an engine restart resumes on its own (#173):
 * with no explicit {@code resume} and no live process, the most recently captured resume
 * id for that session and tool fills in automatically.
 * {@code seed=template} (#537) makes a brand-new {@code claude}/{@code codex}/{@code
 * opencode}/{@code omp} project-console session start with the engine-composed first prompt that
 * tells the agent to read the template #536 committed and build the project — composed
 * by {@link ProjectConsoleService#templateSeedPrompt}, never taken from the client —
 * and records the launch on the project so it happens exactly once; ignored for a
 * shell, for a session that is not a project console's, for a project with no template
 * or one already seeded, for a reattach to a live process, and whenever a
 * {@code resume} is also given (a resumed conversation already has its history).
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
 *
 * <p>With several clients attached to one session, the PTY's size follows the client
 * that most recently reported focus (#574) — see {@link AttachmentSizeArbiter}; a
 * resize from any other attachment is held until that attachment reports focus.
 *
 * <p>Live output is decoded per connection with a {@link StreamingUtf8Decoder} (#634):
 * the PTY is read in fixed-size chunks, and a read boundary can fall inside a
 * multi-byte UTF-8 character, so decoding each chunk on its own would turn the
 * partial bytes on either side into U+FFFD. The decoder carries an incomplete tail
 * over to the next chunk and emits every complete character immediately.
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    private static final char INPUT = '0';
    private static final char RESIZE = '1';
    private static final char FOCUS = '2';

    private final SessionRegistry sessionRegistry;
    private final ProjectConsoleService projectConsoleService;
    private final WorktreeSessionAuthorization authorization;
    private final TerminalHeartbeat heartbeat;
    private final AttachmentSizeArbiter sizeArbiter = new AttachmentSizeArbiter();
    private final Map<String, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

    @Autowired
    public TerminalWebSocketHandler(SessionRegistry sessionRegistry, ProjectConsoleService projectConsoleService,
            WorktreeSessionAuthorization authorization, Clock clock,
            @Value("${locklane.terminal.heartbeat-interval-ms}") long heartbeatIntervalMs) {
        this.sessionRegistry = sessionRegistry;
        this.projectConsoleService = projectConsoleService;
        this.authorization = authorization;
        this.heartbeat = new TerminalHeartbeat(clock, heartbeatIntervalMs);
    }

    /**
     * Test-only: these tests never call {@link #afterConnectionEstablished}, so the
     * heartbeat and authorization (#242) are never exercised.
     */
    public TerminalWebSocketHandler(SessionRegistry sessionRegistry, ProjectConsoleService projectConsoleService) {
        this(sessionRegistry, projectConsoleService, null, Clock.systemUTC(), 20_000L);
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
        // session cookie, so getPrincipal() is never null in practice. A null
        // principal is still treated as unauthorized below rather than passed into
        // the authorization check (which reads a null username as "no caller to
        // check" and would let it through) — this defensive branch must fail
        // closed, not open.
        //
        // The actual decision — may this caller see/attach to this session at all
        // — is #242's project-owner-derived check (ADR-101 Decision 6), replacing
        // #48's "first attach claims it": WorktreeSessionAuthorization resolves the
        // project this session id belongs to and checks the caller against that
        // project's owner_user_id (or admin status), the exact same check the REST
        // listings (IssueWorktreeService, ProjectConsoleService) apply — one
        // implementation, so the two paths can never disagree about the same id.
        String username = wsSession.getPrincipal() != null ? wsSession.getPrincipal().getName() : null;
        if (username == null || !authorization.isVisibleTo(sessionId, username)) {
            wsSession.close(CloseStatus.POLICY_VIOLATION.withReason("You do not have access to this session"));
            return;
        }

        Launch launch = resolveLaunch(sessionId, queryParam(wsSession, "cmd"), queryParam(wsSession, "resume"),
                queryParam(wsSession, "seed"), workingDirectory);
        Integer columns = parseIntParam(wsSession, "cols");
        Integer rows = parseIntParam(wsSession, "rows");
        // Empty for anything that isn't a project console's session id (#139) — a
        // no-op merge for every ordinary worktree/main-checkout session.
        Map<String, String> extraEnvironment = projectConsoleService.environmentFor(sessionId);
        PtySession session = sessionRegistry.attach(sessionId, workingDirectory, launch.command(), username, columns,
                rows, extraEnvironment);
        if (launch.seeded()) {
            // The launch just happened (resolveLaunch only seeds when no live process
            // existed), so this is the one write that turns the seed rule off (#537).
            projectConsoleService.markTemplateSeeded(sessionId, Instant.now());
        }

        // Replay everything produced so far before subscribing, so nothing produced
        // between the snapshot and the subscription taking effect is lost or
        // duplicated — subscribe() only ever delivers output from this point on.
        wsSession.sendMessage(new TextMessage(session.bufferedOutput()));
        StreamingUtf8Decoder decoder = new StreamingUtf8Decoder();
        AutoCloseable subscription = session.subscribe(chunk -> forward(wsSession, decoder, chunk));
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
        try {
            heartbeat.tick();
        } catch (RuntimeException e) {
            log.error("Scheduled terminal heartbeat failed", e);
        }
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
                parseSize(body).flatMap(size -> sizeArbiter.resized(sessionId, wsSession.getId(), size))
                        .ifPresent(size -> session.resize(size.columns(), size.rows()));
            } else if (type == FOCUS) {
                session.markFocused();
                // This attachment is the one the user is looking at now (#574): the
                // PTY takes its size, even one it reported while unfocused.
                sizeArbiter.focused(sessionId, wsSession.getId())
                        .ifPresent(size -> session.resize(size.columns(), size.rows()));
            }
        });
    }

    /** {@code body} is {@code "<columns>x<rows>"} (e.g. {@code "120x40"}); malformed is empty. */
    private static Optional<AttachmentSizeArbiter.Size> parseSize(String body) {
        int separator = body.indexOf('x');
        if (separator < 0) {
            return Optional.empty();
        }
        try {
            int columns = Integer.parseInt(body.substring(0, separator));
            int rows = Integer.parseInt(body.substring(separator + 1));
            return Optional.of(new AttachmentSizeArbiter.Size(columns, rows));
        } catch (NumberFormatException ignored) {
            // silent: not a resize this handler can act on; nothing productive to do
            // with it.
            return Optional.empty();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) throws Exception {
        AutoCloseable subscription = subscriptions.remove(wsSession.getId());
        if (subscription != null) {
            subscription.close();
        }
        heartbeat.untrack(wsSession);
        sizeArbiter.detached(sessionId(wsSession), wsSession.getId());
        // No call into SessionRegistry/PtySession here, deliberately: this connection
        // closing must never stop the session itself.
    }

    /**
     * Forwards one PTY chunk to the client through this connection's decoder (#634).
     * Package-visible for tests. Nothing is sent for a chunk that yields no complete
     * character (e.g. one holding only the first byte of a 3-byte sequence).
     */
    static void forward(WebSocketSession wsSession, StreamingUtf8Decoder decoder, byte[] chunk) {
        if (!wsSession.isOpen()) {
            return;
        }
        String text = decoder.decode(chunk);
        if (text.isEmpty()) {
            return;
        }
        try {
            wsSession.sendMessage(new TextMessage(text));
        } catch (IOException e) {
            // silent: the connection is going away; afterConnectionClosed will clean
            // up the subscription shortly. Nothing productive to do with this failure
            // here.
        }
    }

    /**
     * Decodes a byte stream that arrives in arbitrary chunks as UTF-8 (#634). Every
     * complete character is returned from the {@link #decode} call that completes it;
     * the trailing bytes of a sequence cut off by a chunk boundary (at most three) are
     * held and joined onto the next chunk. Genuinely malformed bytes become U+FFFD, the
     * same as {@code new String(bytes, UTF_8)} did before. One instance per
     * connection; not thread-safe, which matches the one drain thread that feeds it.
     * Package-visible for tests.
     */
    static final class StreamingUtf8Decoder {

        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] pending = new byte[0];

        String decode(byte[] chunk) {
            byte[] input;
            if (pending.length == 0) {
                input = chunk;
            } else {
                input = new byte[pending.length + chunk.length];
                System.arraycopy(pending, 0, input, 0, pending.length);
                System.arraycopy(chunk, 0, input, pending.length, chunk.length);
            }
            ByteBuffer in = ByteBuffer.wrap(input);
            // A UTF-8 byte never decodes to more than one char, so this never overflows.
            CharBuffer out = CharBuffer.allocate(input.length);
            decoder.reset();
            // endOfInput=false: an incomplete trailing sequence is left unread in `in`
            // (underflow) rather than replaced, so it can be completed by the next chunk.
            decoder.decode(in, out, false);
            pending = new byte[in.remaining()];
            in.get(pending);
            out.flip();
            return out.toString();
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
        if (resume == null && cmd != null && (cmd.equals("claude") || cmd.equals("codex") || cmd.equals("opencode") || cmd.equals("omp"))
                && sessionRegistry.find(sessionId).isEmpty()) {
            resume = sessionRegistry.latestResumeId(sessionId, cmd).orElse(null);
        }
        return resolveLaunchCommand(cmd, resume);
    }

    /** The accepted value of the {@code seed} query parameter (#537). */
    static final String SEED_TEMPLATE = "template";

    /**
     * As {@link #resolveLaunchCommand(String, String, String)}, plus #537's seeded
     * launch: when {@code seed} is {@link #SEED_TEMPLATE}, {@code cmd} is an agent, no
     * {@code resume} was given, no live process exists for this session, and
     * {@link ProjectConsoleService#templateSeedPrompt} says this project still owes its
     * seeded console, the command carries the engine-composed prompt and the result is
     * flagged {@code seeded} so the caller records the launch. Anything else resolves
     * exactly as before, with {@code seeded} false. Package-visible for tests.
     */
    Launch resolveLaunch(String sessionId, String cmd, String resume, String seed, Path workingDirectory) {
        if (SEED_TEMPLATE.equals(seed) && resume == null && isAgent(cmd)
                && sessionRegistry.find(sessionId).isEmpty() && projectConsoleService != null) {
            Optional<String> prompt = projectConsoleService.templateSeedPrompt(sessionId, workingDirectory);
            if (prompt.isPresent()) {
                return new Launch(seededLaunchCommand(cmd, prompt.get()), true);
            }
        }
        return new Launch(resolveLaunchCommand(sessionId, cmd, resume), false);
    }

    /** A resolved launch: the command (or {@code null} for the default shell) and whether it was seeded (#537). */
    record Launch(String[] command, boolean seeded) {
    }

    private static boolean isAgent(String cmd) {
        return cmd != null && (cmd.equals("claude") || cmd.equals("codex") || cmd.equals("opencode") || cmd.equals("omp"));
    }

    /**
     * The agent's own "start interactively with this first prompt" shape (#537):
     * {@code claude <prompt>}, {@code codex <prompt>}, and {@code omp <prompt>} take it positionally,
     * {@code opencode --prompt <prompt>} by flag (confirmed against opencode 1.18.25).
     * The prompt travels as one argv element — never through a shell — and is always
     * engine text, so nothing the client sends reaches the process. {@code null} for
     * anything that is not one of the four agents. Package-visible for tests.
     */
    static String[] seededLaunchCommand(String cmd, String prompt) {
        if (cmd == null || prompt == null) {
            return null;
        }
        return switch (cmd) {
            case "claude" -> new String[] {"claude", prompt};
            case "codex" -> new String[] {"codex", prompt};
            case "opencode" -> new String[] {"opencode", "--prompt", prompt};
            case "omp" -> new String[] {"omp", prompt};
            default -> null;
        };
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
            if (cmd.equals("omp")) {
                return new String[] {"omp", "--resume", resume};
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
            // silent: a malformed query parameter falls back to the default size,
            // same as an absent one.
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
