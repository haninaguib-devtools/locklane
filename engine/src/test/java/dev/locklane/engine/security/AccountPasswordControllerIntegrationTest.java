package dev.locklane.engine.security;

import org.junit.jupiter.api.AfterEach;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers #241's self-service done-when over the real endpoint: a signed-in user can
 * replace their own password with the current one, a wrong current password is refused,
 * a blank new password is refused, and the endpoint requires a session at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountPasswordControllerIntegrationTest {

    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "test-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void restoreTheBootstrapPassword() {
        new JdbcTemplate(dataSource).update(
                "UPDATE users SET password_hash = ? WHERE username = ?",
                passwordEncoder.encode(PASSWORD), USERNAME);
    }

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        mockMvc.perform(post("/api/account/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"whatever\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theCorrectCurrentPasswordChangesItAndTheNewOneWorksNextLogin() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/api/account/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a-new-password\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", "a-new-password"))
                .andExpect(status().isOk());
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndLeavesThePasswordUnchanged() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/api/account/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-the-password\",\"newPassword\":\"a-new-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("that password is not correct"));

        mockMvc.perform(post("/api/auth/login")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    void aBlankNewPasswordIsRefused() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/api/account/password").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the new password must not be blank"));
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
