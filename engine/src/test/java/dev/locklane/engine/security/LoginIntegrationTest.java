package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers #47's login/logout done-when: the bootstrap user (seeded by
 * {@link UserBootstrapper}, credentials from {@code locklane.security.bootstrap-*} in
 * test resources' {@code application.yml}) can log in and out over the real endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void theBootstrapUserIsAnAdmin() {
        UserRecord user = userRepository.findByUsername("test-user").orElseThrow();

        assertThat(user.role()).isEqualTo(UserRecord.Role.ADMIN);
    }

    @Test
    void theBootstrapUserCanLogIn() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk());
    }

    /** #240: the login response body names the account's role, for the client's admin-panel gate. */
    @Test
    void aPlainLoginResponseNamesTheAccountsRole() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-user"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void aWrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "not-the-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loggingOutAfterLoggingInSucceeds() throws Exception {
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(post("/api/auth/logout")
                        .session((org.springframework.mock.web.MockHttpSession)
                                loginResult.getRequest().getSession(false)))
                .andExpect(status().isOk());
    }
}
