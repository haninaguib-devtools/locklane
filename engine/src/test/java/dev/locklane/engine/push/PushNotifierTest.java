package dev.locklane.engine.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.ProjectRecord;
import dev.locklane.engine.persistence.ProjectRepository;
import dev.locklane.engine.persistence.TestSqliteDatabases;
import dev.locklane.engine.pty.PtySession;
import dev.locklane.engine.pty.SessionRegistry;
import dev.locklane.engine.security.EncryptionKeyProvider;
import dev.locklane.engine.security.TokenCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers #860's done-when for the bell-to-push rule: a bell on an agent session
 * reaches every subscription of the owning project's owner and nobody else's;
 * quiet and shells push nothing; a gone subscription is forgotten; a failing push
 * never escapes; and the payload is what ngsw-worker.js shows.
 */
class PushNotifierTest {

    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";

    /** Records every send; answers with whatever {@code reply} is, or throws when {@code failing}. */
    private static final class RecordingClient implements WebPushClient {
        final List<URI> endpoints = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final List<String> authorizations = new ArrayList<>();
        Delivery reply = Delivery.DELIVERED;
        boolean failing;

        @Override
        public Delivery send(URI endpoint, byte[] encryptedBody, String authorizationHeader) {
            if (failing) {
                throw new IllegalStateException("boom");
            }
            endpoints.add(endpoint);
            bodies.add(encryptedBody);
            authorizations.add(authorizationHeader);
            return reply;
        }
    }

    private ProjectRepository projects;
    private PushSubscriptionRepository subscriptions;
    private SessionRegistry registry;
    private RecordingClient client;
    private PushNotifier notifier;
    private ProjectRecord alicesProject;
    private final Map<Integer, String> titles = Map.of(7, "Rename the toggle");

    @BeforeEach
    void wire(@TempDir Path tmp) throws IOException {
        projects = TestSqliteDatabases.newProjectRepository(tmp);
        TokenCipher cipher = new TokenCipher(new EncryptionKeyProvider(tmp.resolve("keys").toString()));
        subscriptions = new PushSubscriptionRepository(TestSqliteDatabases.newDataSource(tmp), cipher);
        registry = new SessionRegistry(TestSqliteDatabases.newRepository(tmp));
        client = new RecordingClient();
        Executor inline = Runnable::run;
        notifier = new PushNotifier(registry, projects,
                (projectId, issueNumber) -> Optional.ofNullable(titles.get(issueNumber)), subscriptions, client,
                new VapidKeyPair(EcKeys.generate()), inline, "mailto:ops@example.com", new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), java.time.ZoneOffset.UTC));
        alicesProject = projects.create("alpha", "url", tmp.resolve("alpha"), 1L, Instant.now());
        subscriptions.save(1L, "https://push.example.net/alice-phone", UA_PUBLIC, AUTH_SECRET, Instant.now());
        subscriptions.save(1L, "https://push.example.net/alice-laptop", UA_PUBLIC, AUTH_SECRET, Instant.now());
        subscriptions.save(2L, "https://push.example.net/bob-phone", UA_PUBLIC, AUTH_SECRET, Instant.now());
    }

    @Test
    void aBellOnAnIssuesAgentReachesEveryBrowserOfTheOwnerAndNoOneElse() {
        notifier.onAttentionChange(alicesProject.id() + "-7-rename-toggle", PtySession.AttentionState.WAITING,
                PtySession.WaitingReason.BELL);

        assertThat(client.endpoints).extracting(URI::toString).containsExactly(
                "https://push.example.net/alice-phone", "https://push.example.net/alice-laptop");
        assertThat(client.authorizations).allSatisfy(header -> assertThat(header).startsWith("vapid t=").contains(", k="));
        assertThat(client.bodies).allSatisfy(body -> assertThat(body.length).isGreaterThan(86 + 16));
    }

    @Test
    void quietActiveAndShellsPushNothing() {
        notifier.onAttentionChange(alicesProject.id() + "-7-rename-toggle", PtySession.AttentionState.WAITING,
                PtySession.WaitingReason.QUIET);
        notifier.onAttentionChange(alicesProject.id() + "-7-rename-toggle", PtySession.AttentionState.ACTIVE, null);
        notifier.onAttentionChange(alicesProject.id() + "-shell-7-0123abcd", PtySession.AttentionState.WAITING,
                PtySession.WaitingReason.BELL);
        notifier.onAttentionChange("not-a-session-id", PtySession.AttentionState.WAITING, PtySession.WaitingReason.BELL);

        assertThat(client.endpoints).isEmpty();
    }

    @Test
    void aProjectWithNoSubscribedOwnerPushesNothing(@TempDir Path tmp) {
        ProjectRecord unsubscribed = projects.create("gamma", "url", tmp.resolve("gamma"), 3L, Instant.now());

        notifier.onAttentionChange(unsubscribed.id() + "-console-0123abcd", PtySession.AttentionState.WAITING,
                PtySession.WaitingReason.BELL);
        notifier.onAttentionChange("999-7-no-such-project", PtySession.AttentionState.WAITING,
                PtySession.WaitingReason.BELL);

        assertThat(client.endpoints).isEmpty();
    }

    @Test
    void aGoneSubscriptionIsForgottenAndTheRestKept() {
        client.reply = WebPushClient.Delivery.GONE;

        notifier.onAttentionChange(alicesProject.id() + "-7-rename-toggle", PtySession.AttentionState.WAITING,
                PtySession.WaitingReason.BELL);

        assertThat(subscriptions.findAllOwnedBy(1L)).isEmpty();
        assertThat(subscriptions.findAllOwnedBy(2L)).hasSize(1);
    }

    @Test
    void aFailingPushIsContained() {
        client.failing = true;

        notifier.onAttentionChange(alicesProject.id() + "-7-rename-toggle", PtySession.AttentionState.WAITING,
                PtySession.WaitingReason.BELL);

        assertThat(subscriptions.findAllOwnedBy(1L)).hasSize(2);
    }

    @Test
    void thePayloadIsTheNotificationNgswShowsForAnIssuesAgent() throws Exception {
        String json = notifier.payloadFor(alicesProject.id() + "-7-rename-toggle",
                new PushNotifier.Target(alicesProject.id(), 7), alicesProject);

        JsonNode notification = new ObjectMapper().readTree(json).path("notification");
        assertThat(notification.path("title").asText()).isEqualTo("Agent on #7 is waiting");
        assertThat(notification.path("body").asText()).isEqualTo("Rename the toggle");
        assertThat(notification.path("tag").asText()).isEqualTo(alicesProject.id() + "-7-rename-toggle");
        assertThat(notification.path("renotify").asBoolean()).isTrue();
        assertThat(notification.path("data").path("sessionId").asText()).isEqualTo(alicesProject.id() + "-7-rename-toggle");
        JsonNode click = notification.path("data").path("onActionClick").path("default");
        assertThat(click.path("operation").asText()).isEqualTo("focusLastFocusedOrOpen");
        assertThat(click.path("url").asText()).isEqualTo("/projects/" + alicesProject.id() + "/issues/7");
    }

    @Test
    void thePayloadForAProjectAgentNamesTheProjectAndItsAgentPage() throws Exception {
        // 'console' in the id and the URL is a compatibility surface kept under ADR-112.
        String sessionId = alicesProject.id() + "-console-0123abcd";
        String json = notifier.payloadFor(sessionId, new PushNotifier.Target(alicesProject.id(), null), alicesProject);

        JsonNode notification = new ObjectMapper().readTree(json).path("notification");
        assertThat(notification.path("title").asText()).isEqualTo("Agent is waiting");
        assertThat(notification.path("body").asText()).isEqualTo("alpha");
        assertThat(notification.path("data").path("onActionClick").path("default").path("url").asText())
                .isEqualTo("/projects/" + alicesProject.id() + "/console?session=" + sessionId);
    }

    @Test
    void anIssueWithNoKnownTitleFallsBackToTheProjectName() throws Exception {
        String json = notifier.payloadFor(alicesProject.id() + "-8-x", new PushNotifier.Target(alicesProject.id(), 8), alicesProject);

        assertThat(new ObjectMapper().readTree(json).path("notification").path("body").asText()).isEqualTo("alpha");
    }

    @Test
    void sessionIdsResolveToTheirAgentOrToNothing() {
        assertThat(PushNotifier.targetOf("12-345-fix-the-thing")).contains(new PushNotifier.Target(12, 345));
        assertThat(PushNotifier.targetOf("12-console")).contains(new PushNotifier.Target(12, null));
        assertThat(PushNotifier.targetOf("12-console-0123abcd")).contains(new PushNotifier.Target(12, null));
        assertThat(PushNotifier.targetOf("12-shell-345-0123abcd")).isEmpty();
        assertThat(PushNotifier.targetOf("12-shell-main-0123abcd")).isEmpty();
        assertThat(PushNotifier.targetOf("attention-bell")).isEmpty();
    }
}
