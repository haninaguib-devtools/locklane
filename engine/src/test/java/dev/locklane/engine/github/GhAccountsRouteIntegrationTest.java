package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #550's "authenticated endpoint" done-when over the real security filter chain:
 * {@code SecurityConfig} ends in {@code permitAll}, so this is the test that proves
 * the {@code /api/github/accounts/**} matcher exists — without it a caller's GitHub
 * accounts would be served to anyone. The service is mocked so no real GitHub or
 * {@code gh} is ever reached.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GhAccountsRouteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GhAccountsService service;

    @Test
    void anUnauthenticatedCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/github/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnauthenticatedCallerCannotStartADeviceFlow() throws Exception {
        mockMvc.perform(post("/api/github/accounts/device/start")).andExpect(status().isUnauthorized());
    }

    @Test
    void aLoggedInCallerGetsTheirAccounts() throws Exception {
        when(service.accountsFor(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of(
                new GhAccount(1, 1, "haninaguib", Set.of("repo"), Instant.parse("2026-08-01T00:00:00Z"))));
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/api/github/accounts")
                        .session((MockHttpSession) loginResult.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].login").value("haninaguib"));
    }
}
