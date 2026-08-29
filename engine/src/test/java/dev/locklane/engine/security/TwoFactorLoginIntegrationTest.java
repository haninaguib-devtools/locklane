package dev.locklane.engine.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.BackupCodeRepository;
import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers #89's done-when: login with 2FA on stops short of a session until a code
 * confirms it, login with 2FA off is unchanged, and a wrong code establishes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TwoFactorLoginIntegrationTest {

    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "test-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TotpService totpService;

    @Autowired
    private BackupCodeRepository backupCodeRepository;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void clearTwoFactorState() {
        userRepository.disableTotp(USERNAME);
        userRepository.findByUsername(USERNAME).ifPresent(user -> backupCodeRepository.deleteAll(user.id()));
    }

    @Test
    void loginWithoutTwoFactorEstablishesASessionAsBefore() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/api/auth/me")
                        .session((MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));
    }

    @Test
    void loginWithTwoFactorEnabledSignalsTheChallengeWithoutEstablishingASession() throws Exception {
        enableTwoFactor();

        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorRequired").value(true))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyingTheRightCodeCompletesLoginAndEstablishesASession() throws Exception {
        String secret = enableTwoFactor().secret();
        MockHttpSession session = loginPendingTwoFactor();

        mockMvc.perform(verifyWith(session, totpService.currentCode(secret, Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));
    }

    @Test
    void verifyingAWrongCodeIsRejectedAndCreatesNoSession() throws Exception {
        String secret = enableTwoFactor().secret();
        MockHttpSession session = loginPendingTwoFactor();
        String wrongCode = wrongCodeFor(secret);

        mockMvc.perform(verifyWith(session, wrongCode))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("that code is not correct"));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());

        // The pending login survives a wrong guess, so the right code still works after.
        mockMvc.perform(verifyWith(session, totpService.currentCode(secret, Instant.now())))
                .andExpect(status().isOk());
    }

    @Test
    void verifyingWithNoPendingLoginIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("no login is pending a two-factor code"));
    }

    @Test
    void verifyingWithABackupCodeCompletesLoginAndConsumesIt() throws Exception {
        String backupCode = enableTwoFactor().backupCodes().get(0);
        MockHttpSession session = loginPendingTwoFactor();

        mockMvc.perform(verifyWith(session, backupCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));
    }

    @Test
    void aBackupCodeCanBeUsedOnlyOnce() throws Exception {
        String backupCode = enableTwoFactor().backupCodes().get(0);

        MockHttpSession firstSession = loginPendingTwoFactor();
        mockMvc.perform(verifyWith(firstSession, backupCode)).andExpect(status().isOk());

        MockHttpSession secondSession = loginPendingTwoFactor();
        mockMvc.perform(verifyWith(secondSession, backupCode))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("that code is not correct"));
    }

    @Test
    void aBackupCodeIsAcceptedRegardlessOfCase() throws Exception {
        String backupCode = enableTwoFactor().backupCodes().get(0).toLowerCase();
        MockHttpSession session = loginPendingTwoFactor();

        mockMvc.perform(verifyWith(session, backupCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));
    }

    /** Enrolls and confirms 2FA for {@link #USERNAME} via a plain (2FA-off) login. */
    private EnabledTwoFactor enableTwoFactor() throws Exception {
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        String body = mockMvc.perform(post("/api/account/2fa/enroll").session(session))
                .andReturn().getResponse().getContentAsString();
        JsonNode enrollResponse = json.readTree(body);
        String secret = enrollResponse.get("manualKey").asText();

        String confirmBody = mockMvc.perform(post("/api/account/2fa/confirm").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totpService.currentCode(secret, Instant.now()) + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode confirmResponse = json.readTree(confirmBody);
        List<String> backupCodes = new ArrayList<>();
        confirmResponse.get("backupCodes").forEach(node -> backupCodes.add(node.asText()));
        return new EnabledTwoFactor(secret, backupCodes);
    }

    private record EnabledTwoFactor(String secret, List<String> backupCodes) {
    }

    private MockHttpSession loginPendingTwoFactor() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verifyWith(
            MockHttpSession session, String code) {
        return post("/api/auth/2fa/verify").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}");
    }

    private String wrongCodeFor(String secret) {
        for (int candidate = 0; candidate < 10; candidate++) {
            String code = String.format("%06d", candidate);
            if (!totpService.verify(secret, code, Instant.now())) {
                return code;
            }
        }
        throw new IllegalStateException("Could not find a code this secret rejects");
    }
}
