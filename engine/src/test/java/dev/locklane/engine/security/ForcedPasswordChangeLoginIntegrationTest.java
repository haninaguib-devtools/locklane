package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers #241's forced-first-login done-when: login for an account with {@code
 * must_change_password} set stops short of a session until a new password is chosen --
 * the same shape #89 already proved for a pending 2FA code (see
 * {@code TwoFactorLoginIntegrationTest}).
 *
 * <p>The bootstrap user has no admin-side "create with a temporary password" flow to
 * drive yet (#240, out of scope here) -- {@code must_change_password} is flipped directly
 * in the database, exactly as it would be by that future flow, and restored after each
 * test so this account's flag never leaks into any other test relying on it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForcedPasswordChangeLoginIntegrationTest {

    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "test-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void restoreTheBootstrapAccount() {
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, must_change_password = 0 WHERE username = ?",
                passwordEncoder.encode(PASSWORD), USERNAME);
    }

    private void requirePasswordChange() {
        jdbcTemplate.update("UPDATE users SET must_change_password = 1 WHERE username = ?", USERNAME);
    }

    @Test
    void loginWithoutMustChangePasswordEstablishesASessionAsBefore() throws Exception {
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
    void loginWithMustChangePasswordSignalsTheGateWithoutEstablishingASession() throws Exception {
        requirePasswordChange();

        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePasswordRequired").value(true))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingToTheNewPasswordCompletesLoginClearsTheFlagAndEstablishesASession() throws Exception {
        requirePasswordChange();
        MockHttpSession session = loginPendingPasswordChange();

        mockMvc.perform(changeWith(session, PASSWORD, "a-brand-new-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME));
        assertThat(userRepository.findByUsername(USERNAME).orElseThrow().mustChangePassword()).isFalse();

        // And the new password is what a fresh login now takes.
        mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", "a-brand-new-password"))
                .andExpect(status().isOk());
    }

    @Test
    void aWrongCurrentPasswordIsRejectedAndCreatesNoSession() throws Exception {
        requirePasswordChange();
        MockHttpSession session = loginPendingPasswordChange();

        mockMvc.perform(changeWith(session, "not-the-password", "a-brand-new-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("that password is not correct"));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findByUsername(USERNAME).orElseThrow().mustChangePassword())
                .as("a rejected attempt must not clear the gate")
                .isTrue();

        // The pending login survives a wrong guess, so the right password still works after.
        mockMvc.perform(changeWith(session, PASSWORD, "a-brand-new-password"))
                .andExpect(status().isOk());
    }

    @Test
    void aBlankNewPasswordIsRejected() throws Exception {
        requirePasswordChange();
        MockHttpSession session = loginPendingPasswordChange();

        mockMvc.perform(changeWith(session, PASSWORD, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the new password must not be blank"));

        assertThat(userRepository.findByUsername(USERNAME).orElseThrow().mustChangePassword()).isTrue();
    }

    @Test
    void changingWithNoPendingLoginIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/password/change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("no login is pending a password change"));
    }

    private MockHttpSession loginPendingPasswordChange() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changeWith(
            MockHttpSession session, String currentPassword, String newPassword) {
        return post("/api/auth/password/change").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}");
    }
}
