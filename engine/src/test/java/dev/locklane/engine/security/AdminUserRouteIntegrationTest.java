package dev.locklane.engine.security;

import dev.locklane.engine.persistence.UserRecord;
import dev.locklane.engine.persistence.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers #240's route-gating done-when over the real security filter chain: an
 * unauthenticated caller gets 401, an authenticated non-admin gets 403 rather than
 * served, and the bootstrap admin can reach {@code /api/admin/users}. Business logic
 * (create/delete behavior, cascade-delete) is covered at the controller level by
 * {@link AdminUserControllerTest}; this class only exercises {@code SecurityConfig}'s
 * {@code hasRole("ADMIN")} matcher.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserRouteIntegrationTest {

    private static final String ADMIN_USERNAME = "test-user";
    private static final String ADMIN_PASSWORD = "test-password";
    private static final String ORDINARY_USERNAME = "admin-route-test-ordinary-user";
    private static final String ORDINARY_PASSWORD = "ordinary-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @AfterEach
    void removeTheOrdinaryUser() {
        userRepository.findByUsername(ORDINARY_USERNAME)
                .ifPresent(user -> userRepository.deleteById(user.id()));
    }

    @Test
    void anUnauthenticatedCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAuthenticatedNonAdminIsForbidden() throws Exception {
        userRepository.create(ORDINARY_USERNAME, passwordEncoder.encode(ORDINARY_PASSWORD), Instant.now(),
                UserRecord.Role.USER);
        MockHttpSession session = login(ORDINARY_USERNAME, ORDINARY_PASSWORD);

        mockMvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users").session(session)
                        .contentType("application/json").content("{\"username\":\"nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/users/1").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void theBootstrapAdminCanListAndCreateUsers() throws Exception {
        MockHttpSession session = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/admin/users").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users").session(session)
                        .contentType("application/json")
                        .content("{\"username\":\"" + ORDINARY_USERNAME + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value(ORDINARY_USERNAME))
                .andExpect(jsonPath("$.temporaryPassword").exists());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
