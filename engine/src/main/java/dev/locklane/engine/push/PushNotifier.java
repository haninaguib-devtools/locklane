package dev.locklane.engine.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.pty.PtySession;
import dev.locklane.engine.pty.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pushes "an agent is waiting" to the browsers of the account that owns the agent's
 * project (#860, ADR-114): the one thing the in-app notification (#859) cannot do
 * once the app is closed. Listens to every session's attention transitions through
 * {@link SessionRegistry#addAttentionListener} and acts on exactly one of them --
 * waiting with reason {@code bell}, the precise signal an agent CLI's own hook
 * rings (#855–#858) -- never on the {@code quiet} fallback, and never for a shell
 * ({@code <projectId>-shell-…}), whose bell is whatever the person typed.
 *
 * <p>The listener runs on the session's own output-drain thread, so nothing slow
 * happens there: each push is handed to a virtual-thread executor, and every
 * failure is contained, the same guard {@code SessionRegistry}'s resume-id scanner
 * already has -- a push service being down must never stall an agent's terminal.
 *
 * <p>The payload is the shape Angular's own service worker ({@code ngsw-worker.js})
 * shows without any custom worker code: a {@code notification} object it hands to
 * {@code showNotification} as-is, tagged with the session id so it replaces the
 * in-app notification for the same agent rather than stacking, and an
 * {@code onActionClick} the worker acts on itself -- focus the app if a window is
 * open (the page then routes to the agent through its own {@code notificationClicks}
 * stream), else open the agent's own URL.
 */
@Component
public class PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);
    // Mirrors IssueWorktreeService's PROJECT_AND_ISSUE_PREFIXED / PROJECT_AGENT_SESSION_PREFIXED
    // ('console' is the persisted id shape, a compatibility surface kept under ADR-112).
    private static final Pattern ISSUE_AGENT = Pattern.compile("^(\\d+)-(\\d+)-");
    private static final Pattern PROJECT_AGENT = Pattern.compile("^(\\d+)-console(-.+)?$");

    /** The issue's title for the notification body, if the project's GitHub data has it. */
    @FunctionalInterface
    public interface IssueTitles {
        Optional<String> titleOf(long projectId, int issueNumber);
    }

    private final ProjectRepository projectRepository;
    private final IssueTitles issueTitles;
    private final PushSubscriptionRepository subscriptions;
    private final WebPushClient client;
    private final VapidKeyPair keys;
    private final Executor executor;
    private final String contact;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Autowired
    public PushNotifier(SessionRegistry sessionRegistry, ProjectRepository projectRepository, IssueTitles issueTitles,
            PushSubscriptionRepository subscriptions, WebPushClient client, VapidKeyPair keys,
            @Qualifier("pushExecutor") Executor executor, @Value("${locklane.push.contact}") String contact,
            ObjectMapper objectMapper) {
        this(sessionRegistry, projectRepository, issueTitles, subscriptions, client, keys, executor, contact,
                objectMapper, Clock.systemUTC());
    }

    PushNotifier(SessionRegistry sessionRegistry, ProjectRepository projectRepository, IssueTitles issueTitles,
            PushSubscriptionRepository subscriptions, WebPushClient client, VapidKeyPair keys, Executor executor,
            String contact, ObjectMapper objectMapper, Clock clock) {
        this.projectRepository = projectRepository;
        this.issueTitles = issueTitles;
        this.subscriptions = subscriptions;
        this.client = client;
        this.keys = keys;
        this.executor = executor;
        this.contact = contact;
        this.objectMapper = objectMapper;
        this.clock = clock;
        sessionRegistry.addAttentionListener(this::onAttentionChange);
    }

    void onAttentionChange(String sessionId, PtySession.AttentionState state, PtySession.WaitingReason reason,
            String message) {
        if (state != PtySession.AttentionState.WAITING || reason != PtySession.WaitingReason.BELL) {
            return;
        }
        Optional<Target> target = targetOf(sessionId);
        if (target.isEmpty()) {
            return;
        }
        String body = message != null && !message.isBlank() ? message : null;
        executor.execute(() -> {
            try {
                push(sessionId, target.get(), body);
            } catch (RuntimeException e) {
                log.warn("Push for session {} failed", sessionId, e);
            }
        });
    }

    private void push(String sessionId, Target target, String message) {
        Optional<ProjectRecord> project = projectRepository.findById(target.projectId());
        if (project.isEmpty()) {
            return;
        }
        List<PushSubscriptionRecord> owned = subscriptions.findAllOwnedBy(project.get().ownerUserId());
        if (owned.isEmpty()) {
            return;
        }
        byte[] payload = payloadFor(sessionId, target, project.get(), message).getBytes(StandardCharsets.UTF_8);
        for (PushSubscriptionRecord subscription : owned) {
            deliver(subscription, payload);
        }
    }

    private void deliver(PushSubscriptionRecord subscription, byte[] payload) {
        URI endpoint = URI.create(subscription.endpoint());
        byte[] body = WebPushEncryptor.encrypt(payload, EcKeys.fromBase64Url(subscription.p256dh()),
                EcKeys.fromBase64Url(subscription.auth()));
        String authorization = VapidSigner.authorizationHeader(keys, endpoint, contact, clock.instant());
        if (client.send(endpoint, body, authorization) == WebPushClient.Delivery.GONE) {
            subscriptions.deleteByEndpoint(subscription.endpoint());
        }
    }

    /** The JSON {@code ngsw-worker.js} shows as a notification; see the class comment. */
    String payloadFor(String sessionId, Target target, ProjectRecord project, String message) {
        String title;
        String body;
        String url;
        if (target.issueNumber() != null) {
            title = "Agent on #" + target.issueNumber() + " is waiting";
            body = message != null ? message : issueTitles.titleOf(project.id(), target.issueNumber()).orElse(project.name());
            url = "/projects/" + project.id() + "/issues/" + target.issueNumber();
        } else {
            title = "Agent is waiting";
            body = message != null ? message : project.name();
            // 'console' is the route path segment -- a compatibility surface kept under ADR-112.
            url = "/projects/" + project.id() + "/console?session=" + sessionId;
        }
        Map<String, Object> action = Map.of("operation", "focusLastFocusedOrOpen", "url", url);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("onActionClick", Map.of("default", action));
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title);
        notification.put("body", body);
        notification.put("tag", sessionId);
        notification.put("renotify", true);
        notification.put("data", data);
        try {
            return objectMapper.writeValueAsString(Map.of("notification", notification));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode the push payload", e);
        }
    }

    /** Which agent a session id names -- empty for a shell or anything else that is not an agent. */
    static Optional<Target> targetOf(String sessionId) {
        Matcher issue = ISSUE_AGENT.matcher(sessionId);
        if (issue.find()) {
            return Optional.of(new Target(Long.parseLong(issue.group(1)), Integer.parseInt(issue.group(2))));
        }
        Matcher projectAgent = PROJECT_AGENT.matcher(sessionId);
        if (projectAgent.matches()) {
            return Optional.of(new Target(Long.parseLong(projectAgent.group(1)), null));
        }
        return Optional.empty();
    }

    /** An agent's project and, for an issue's agent, its issue. */
    record Target(long projectId, Integer issueNumber) {
    }
}
