package dev.locklane.engine.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    /**
     * A spy, not a plain {@code @Autowired} bean: {@link
     * #confirmDoesNotEnableTwoFactorAgainstASecretReplacedMidRequest()} needs to inject a
     * concurrent enrollment into the exact instant between {@code confirm}'s read of the pending
     * secret and its update, which no amount of sequential setup can reproduce — a real second
     * request would have to land in a window measured in nanoseconds. Every other test uses this
     * field exactly as a plain {@code UserRepository}; a spy with nothing stubbed just delegates.
     */
    @MockitoSpyBean
    private UserRepository userRepository;

    @Autowired
    private TotpService totpService;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * The test database is a file that outlives a single test, so 2FA state has to be cleared
     * on the way in as well as out — otherwise the order tests happen to run in decides whether
     * an enrollment is already pending. Resetting the spy here too means a stub left behind by
     * one test (deliberately, to simulate a race) can never leak into the next.
     */
    @BeforeEach
    @AfterEach
    void clearTwoFactorState() {
        Mockito.reset(userRepository);
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

    /**
     * The hole #106 closes: a second enrollment (someone else's, on a stolen session, or the
     * same user re-scanning) can replace the pending secret in the instant between {@code
     * confirm} reading it and updating the row. Before the fix, the UPDATE was scoped only to
     * "some secret is present", so it would enable 2FA anyway — against the *new* secret, even
     * though the code the user typed only ever proved they held the *old* one.
     *
     * <p>A real race would need another request to land in a window a few machine instructions
     * wide, which no amount of thread juggling reproduces reliably. Instead, the spy intercepts
     * {@code confirm}'s own read of the pending secret and, right after it returns that secret to
     * the caller (exactly as a genuine read would), performs the replacement as a side effect —
     * so {@code confirm} verifies the code against the secret it actually read, then updates
     * against a row that has already moved on. Everything else in {@code confirm} runs
     * unmodified.
     */
    @Test
    void confirmDoesNotEnableTwoFactorAgainstASecretReplacedMidRequest() throws Exception {
        MockHttpSession session = login();
        String firstSecret = enroll(session).get("manualKey").asText();
        String validCodeForFirstSecret = totpService.currentCode(firstSecret, Instant.now());

        Mockito.doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            userRepository.startTotpEnrollment(USERNAME, "replaced-by-a-second-enrollment");
            return result;
        }).when(userRepository).findByUsername(USERNAME);

        mockMvc.perform(confirmWith(session, validCodeForFirstSecret))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("that enrollment was cleared before it could be confirmed; start again"));

        Mockito.reset(userRepository);
        assertThat(userRepository.findByUsername(USERNAME).orElseThrow().totpEnabled())
                .as("the code only ever proved the FIRST secret; 2FA must not switch on against "
                        + "whatever secret is on the row by the time the update runs")
                .isFalse();
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
