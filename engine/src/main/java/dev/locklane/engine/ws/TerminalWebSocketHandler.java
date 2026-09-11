package dev.locklane.engine.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.ProjectAgentSessionService;
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
import java.util.Arrays;
import java.util.List;
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
 * opencode}/{@code omp} project agent session start with the engine-composed first prompt that
 * tells the agent to read the template #536 committed and build the project — composed
 * by {@link ProjectAgentSessionService#templateSeedPrompt}, never taken from the client —
 * and records the launch on the project so it happens exactly once; ignored for a
 * shell, for a session that is not a project agent session's, for a project with no template
 * or one already seeded, for a reattach to a live process, and whenever a
 * {@code resume} is also given (a resumed conversation already has its history).
 * {@code cols}/{@code rows} size a brand-new session's PTY to the browser terminal's
 * actual size instead of a hardcoded default (#62); once attached, later size changes
 * arrive as resize messages (see below), not new query parameters. A brand-new
 * session also gets whatever extra environment {@link ProjectAgentSessionService}
 * resolves for its id (#139) — {@code GH_TOKEN} for a project agent session, nothing for
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
 *
 * <p>Every {@code claude} launch this handler composes — plain, seeded-prompt and
 * resume alike — also carries {@code --settings} plus one JSON argv element wiring
 * three of Claude Code's own hooks to ring the terminal bell (#855, ADR-113): the
 * engine's bell detection (#130) then fires the instant a turn ends or Claude Code is
 * waiting on the user, precisely, rather than only once output has gone quiet for
 * {@code PtySession.QUIESCENCE_THRESHOLD_MS}. See {@link #withClaudeBellHooks}.
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    private static final char INPUT = '0';
    private static final char RESIZE = '1';
    private static final char FOCUS = '2';

    private final SessionRegistry sessionRegistry;
    private final ProjectAgentSessionService projectAgentSessionService;
    private final WorktreeSessionAuthorization authorization;
    private final TerminalHeartbeat heartbeat;
    private final AttachmentSizeArbiter sizeArbiter = new AttachmentSizeArbiter();
    private final Map<String, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

    @Autowired
    public TerminalWebSocketHandler(SessionRegistry sessionRegistry, ProjectAgentSessionService projectAgentSessionService,
            WorktreeSessionAuthorization authorization, Clock clock,
            @Value("${locklane.terminal.heartbeat-interval-ms}") long heartbeatIntervalMs) {
        this.sessionRegistry = sessionRegistry;
        this.projectAgentSessionService = projectAgentSessionService;
        this.authorization = authorization;
        this.heartbeat = new TerminalHeartbeat(clock, heartbeatIntervalMs);
    }

    /**
     * Test-only: these tests never call {@link #afterConnectionEstablished}, so the
     * heartbeat and authorization (#242) are never exercised.
     */
    public TerminalWebSocketHandler(SessionRegistry sessionRegistry, ProjectAgentSessionService projectAgentSessionService) {
        this(sessionRegistry, projectAgentSessionService, null, Clock.systemUTC(), 20_000L);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession connection) throws Exception {
        // Every write to this connection — the buffered replay below, each PTY chunk the
        // drain thread forwards, the heartbeat's ping — goes through one serializing
        // wrapper (#761); the raw session is never handed to any writer. The heartbeat
        // and the subscription map key by id, which the wrapper delegates, so the raw
        // session Spring hands afterConnectionClosed still finds both entries.
        WebSocketSession wsSession = TerminalHeartbeat.serialized(connection);
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
        // listings (IssueWorktreeService, ProjectAgentSessionService) apply — one
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
        // Empty for anything that isn't a project agent session's session id (#139) — a
        // no-op merge for every ordinary worktree/main-checkout session.
        Map<String, String> extraEnvironment = projectAgentSessionService.environmentFor(sessionId);
        PtySession session = sessionRegistry.attach(sessionId, workingDirectory, launch.command(), username, columns,
                rows, extraEnvironment);
        if (launch.seeded()) {
            // The launch just happened (resolveLaunch only seeds when no live process
            // existed), so this is the one write that turns the seed rule off (#537).
            projectAgentSessionService.markTemplateSeeded(sessionId, Instant.now());
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
        } catch (RuntimeException e) {
            // Contained here, per connection (#761): a write the serializing wrapper
            // refused — a client that stopped reading, past its send-time or buffer
            // limit — is this one connection's problem, and letting it escape into
            // PtySession's drain loop would stop output for every client attached to
            // the session. Closing it hands cleanup to afterConnectionClosed like any
            // other close.
            log.debug("Forwarding output to session {} failed; closing", wsSession.getId(), e);
            try {
                wsSession.close(CloseStatus.SERVER_ERROR);
            } catch (IOException | RuntimeException ignored) {
                // silent: already going away; nothing productive to do with this
                // failure here.
            }
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
     * {@link ProjectAgentSessionService#templateSeedPrompt} says this project still owes its
     * seeded agent session, the command carries the engine-composed prompt and the result is
     * flagged {@code seeded} so the caller records the launch. Anything else resolves
     * exactly as before, with {@code seeded} false. Package-visible for tests.
     */
    Launch resolveLaunch(String sessionId, String cmd, String resume, String seed, Path workingDirectory) {
        if (SEED_TEMPLATE.equals(seed) && resume == null && isAgent(cmd)
                && sessionRegistry.find(sessionId).isEmpty() && projectAgentSessionService != null) {
            Optional<String> prompt = projectAgentSessionService.templateSeedPrompt(sessionId, workingDirectory);
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
            case "claude" -> withClaudeBellHooks(new String[] {"claude", prompt});
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
                return withClaudeBellHooks(new String[] {"claude", "--resume", resume});
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
        return cmd.equals("claude") ? withClaudeBellHooks(new String[] {cmd}) : new String[] {cmd};
    }

    // #855: rings the engine's own agent-agnostic bell signal (#130), never the
    // agent-specific escape sequences Claude Code's own notification channel would
    // otherwise need Locklane-specific configuration to produce. Writes to the
    // controlling terminal, not stdout -- a hook's stdout reaches Claude Code itself,
    // never the screen, and inside a Locklane tab the controlling terminal is the
    // engine's own PTY, which is exactly what PtySession's bell scanner watches.
    private static final String BELL_HOOK_COMMAND = "printf '\\a' > /dev/tty";

    // ADR-113: Locklane wires each agent CLI's own hook mechanism to ring the bell,
    // rather than relying on any notification channel the CLI ships with -- the
    // engine's contract stays the bare bell PtySession already scans for, and the
    // user installs and configures nothing. One hook command answers all three
    // points a turn can stop at: the Stop event when a turn ends in prose, a
    // PreToolUse hook matched on AskUserQuestion for a structured question about to
    // be shown, and a Notification hook matched on permission_prompt for an approval
    // pending.
    private static final String CLAUDE_BELL_HOOKS_SETTINGS_JSON = buildClaudeBellHooksSettingsJson();

    private static String buildClaudeBellHooksSettingsJson() {
        Map<String, Object> hook = Map.of("type", "command", "command", BELL_HOOK_COMMAND);
        Map<String, Object> stopMatcher = Map.of("hooks", List.of(hook));
        Map<String, Object> askQuestionMatcher = Map.of("matcher", "AskUserQuestion", "hooks", List.of(hook));
        Map<String, Object> permissionPromptMatcher = Map.of("matcher", "permission_prompt", "hooks", List.of(hook));
        Map<String, Object> hooks = Map.of(
                "Stop", List.of(stopMatcher),
                "PreToolUse", List.of(askQuestionMatcher),
                "Notification", List.of(permissionPromptMatcher));
        try {
            return new ObjectMapper().writeValueAsString(Map.of("hooks", hooks));
        } catch (JsonProcessingException e) {
            // Unreachable: every value above is a plain String, Map or List -- Jackson
            // never fails to serialize those. A RuntimeException here would otherwise
            // be silently swallowed by this field's static initializer.
            throw new IllegalStateException("Failed to build Claude Code's bell-hook settings JSON", e);
        }
    }

    /**
     * Appends {@code --settings} and the bell-hooks JSON (#855, ADR-113) to a
     * {@code claude} argv. Every caller here already knows {@code command[0]} is
     * {@code "claude"}; kept as a precondition rather than checked again, since a
     * private, package-internal helper with exactly three call sites (all literal
     * {@code claude} argvs) has no other caller to guard against.
     */
    private static String[] withClaudeBellHooks(String[] command) {
        String[] withSettings = Arrays.copyOf(command, command.length + 2);
        withSettings[command.length] = "--settings";
        withSettings[command.length + 1] = CLAUDE_BELL_HOOKS_SETTINGS_JSON;
        return withSettings;
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
