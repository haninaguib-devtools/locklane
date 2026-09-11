package dev.locklane.engine.push;

import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers #860's done-when over the real endpoints: every route under /api/push
 * needs a session (SecurityConfig's matcher -- without it, anyRequest().permitAll()
 * would let anyone in), the public key is the raw P-256 point, and a subscription
 * is stored for, and removed by, the signed-in account only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PushSubscriptionControllerIntegrationTest {

    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "test-password";
    private static final String ENDPOINT = "https://push.example.net/send/integration";
    private static final String SUBSCRIPTION = "{\"endpoint\":\"" + ENDPOINT + "\",\"keys\":{"
            + "\"p256dh\":\"BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4\","
            + "\"auth\":\"BTBZMqHH6r4Tts7J_aSIgg\"}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PushSubscriptionRepository repository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void forgetTheSubscription() {
        repository.deleteByEndpoint(ENDPOINT);
    }

    @Test
    void everyRouteRequiresASession() throws Exception {
        mockMvc.perform(get("/api/push/vapid-public-key")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(SUBSCRIPTION))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/push/subscriptions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + ENDPOINT + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thePublicKeyIsTheRawPointBase64Url() throws Exception {
        mockMvc.perform(get("/api/push/vapid-public-key").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey", matchesPattern("^B[A-Za-z0-9_-]{86}$")));
    }

    @Test
    void subscribingStoresTheSubscriptionForTheSignedInAccountAndUnsubscribingRemovesIt() throws Exception {
        MockHttpSession session = login();
        long userId = userRepository.findByUsername(USERNAME).orElseThrow().id();

        mockMvc.perform(post("/api/push/subscriptions").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(SUBSCRIPTION))
                .andExpect(status().isNoContent());
        assertThat(repository.findAllOwnedBy(userId)).extracting(PushSubscriptionRecord::endpoint).contains(ENDPOINT);

        mockMvc.perform(delete("/api/push/subscriptions").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"" + ENDPOINT + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(repository.findAllOwnedBy(userId)).extracting(PushSubscriptionRecord::endpoint).doesNotContain(ENDPOINT);
    }

    @Test
    void aMalformedSubscriptionIsRefused() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/api/push/subscriptions").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"http://insecure.example/x\",\"keys\":{\"p256dh\":\"BCVx\",\"auth\":\"BTBZ\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("endpoint must be an https URL"));
        mockMvc.perform(post("/api/push/subscriptions").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + ENDPOINT + "\",\"keys\":{\"p256dh\":\"BCVx\",\"auth\":\"BTBZMqHH6r4Tts7J_aSIgg\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("keys.p256dh is not a P-256 public key"));
    }

    private MockHttpSession login() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
