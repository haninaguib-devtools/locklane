package dev.locklane.engine.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.UserRecord;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers #88's done-when over the real endpoints: enrolling, confirming with a valid and with
 * an invalid code, reading status, and disabling with and without the current password.
 *
 * <p>The valid code is produced from the manual key the enroll response hands back, using the
 * same generator the RFC 6238 vectors in {@link TotpServiceTest} pin down — so a code that
 * confirms here is a code a real authenticator app would have shown.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountTwoFactorIntegrationTest {

    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "test-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TotpService totpService;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * The test database is a file that outlives a single test, so 2FA state has to be cleared
     * on the way in as well as out — otherwise the order tests happen to run in decides whether
     * an enrollment is already pending.
     */
    @BeforeEach
    @AfterEach
    void clearTwoFactorState() {
        userRepository.disableTotp(USERNAME);
    }

    @Test
    void everyEndpointRefusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/account/2fa/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/account/2fa/enroll")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/account/2fa/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/account/2fa/disable")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusIsOffBeforeAnythingHappens() throws Exception {
        mockMvc.perform(get("/api/account/2fa/status").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void enrollReturnsAScannableQrCodeAndAManualKeyAndStoresTheSecretEncrypted() throws Exception {
        JsonNode body = enroll(login());

        assertThat(body.get("qrCodeDataUri").asText()).startsWith("data:image/png;base64,");
        assertThat(body.get("qrCodeDataUri").asText().length())
                .as("a data URI with an actual image behind it, not an empty prefix")
                .isGreaterThan(200);

        String manualKey = body.get("manualKey").asText();
        assertThat(manualKey).matches("[A-Z2-7]{32}");
        assertThat(body.get("otpauthUri").asText())
                .startsWith("otpauth://totp/Locklane:" + USERNAME)
                .contains("secret=" + manualKey);

        UserRecord stored = userRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(stored.totpSecret()).isNotNull();
        assertThat(stored.totpSecret())
                .as("the column must never hold the Base32 secret in the clear")
                .isNotEqualTo(manualKey);
        assertThat(stored.totpEnabled())
                .as("scanning a code is not proof the app holds it — 2FA stays off until confirm")
                .isFalse();
    }

    @Test
    void statusStaysOffWhileAnEnrollmentIsStillUnconfirmed() throws Exception {
        MockHttpSession session = login();
        enroll(session);

        mockMvc.perform(get("/api/account/2fa/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void confirmWithAValidCodeTurnsTwoFactorOn() throws Exception {
        MockHttpSession session = login();
        String secret = enroll(session).get("manualKey").asText();

        mockMvc.perform(confirmWith(session, totpService.currentCode(secret, Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/account/2fa/status").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(userRepository.findByUsername(USERNAME).orElseThrow().totpEnabled()).isTrue();
    }

    @Test
    void confirmWithAnInvalidCodeIsRejectedAndLeavesTheEnrollmentPending() throws Exception {
        MockHttpSession session = login();
        String secret = enroll(session).get("manualKey").asText();
        String wrongCode = wrongCodeFor(secret);

        mockMvc.perform(confirmWith(session, wrongCode))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("that code is not correct"));

        UserRecord stored = userRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(stored.totpEnabled()).isFalse();
        assertThat(stored.totpSecret())
                .as("a mistyped code should not throw away the QR the user already scanned")
                .isNotNull();

        // And the right code still works afterwards, from the same enrollment.
        mockMvc.perform(confirmWith(session, totpService.currentCode(secret, Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void confirmWithNoEnrollmentInProgressIsRejected() throws Exception {
        mockMvc.perform(confirmWith(login(), "000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("no enrollment in progress"));
    }

    @Test
    void enrollingAgainBeforeConfirmingReplacesThePendingSecret() throws Exception {
        MockHttpSession session = login();
        String first = enroll(session).get("manualKey").asText();
        String second = enroll(session).get("manualKey").asText();

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(confirmWith(session, totpService.currentCode(first, Instant.now())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(confirmWith(session, totpService.currentCode(second, Instant.now())))
                .andExpect(status().isOk());
    }

    @Test
    void enrollingWhileAlreadyEnabledIsRefused() throws Exception {
        MockHttpSession session = login();
        enable(session);

        mockMvc.perform(post("/api/account/2fa/enroll").session(session))
                .andExpect(status().isConflict());
    }

    @Test
    void disableWithTheCurrentPasswordClearsTheSecretAndTurnsTwoFactorOff() throws Exception {
        MockHttpSession session = login();
        enable(session);

        mockMvc.perform(post("/api/account/2fa/disable").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        UserRecord stored = userRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(stored.totpEnabled()).isFalse();
        assertThat(stored.totpSecret()).as("the secret is forgotten, not merely switched off").isNull();
    }

    @Test
    void disableWithTheWrongPasswordLeavesTwoFactorOn() throws Exception {
        MockHttpSession session = login();
        enable(session);

        mockMvc.perform(post("/api/account/2fa/disable").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"not-the-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("that password is not correct"));

        UserRecord stored = userRepository.findByUsername(USERNAME).orElseThrow();
        assertThat(stored.totpEnabled())
                .as("a session cookie alone must not be able to strip a factor off the account")
                .isTrue();
        assertThat(stored.totpSecret()).isNotNull();
    }

    private MockHttpSession login() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private JsonNode enroll(MockHttpSession session) throws Exception {
        String body = mockMvc.perform(post("/api/account/2fa/enroll").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    /** Enrolls and confirms, leaving the account with 2FA genuinely on. */
    private void enable(MockHttpSession session) throws Exception {
        String secret = enroll(session).get("manualKey").asText();
        mockMvc.perform(confirmWith(session, totpService.currentCode(secret, Instant.now())))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmWith(
            MockHttpSession session, String code) {
        return post("/api/account/2fa/confirm").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}");
    }

    /**
     * A six-digit code this secret does <em>not</em> currently accept. Searched for rather than
     * hard-coded or derived by arithmetic: verification accepts three codes at any moment (the
     * current step and one either side for clock skew), so the only reliable way to name a
     * wrong one is to ask what would be rejected.
     */
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
