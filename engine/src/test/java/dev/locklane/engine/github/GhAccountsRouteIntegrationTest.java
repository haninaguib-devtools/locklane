package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #532's "authenticated endpoint" done-when over the real security filter chain:
 * {@code SecurityConfig} ends in {@code permitAll}, so this is the test that proves the
 * {@code /api/github/**} matcher exists — without it the host's list of GitHub logins
 * would be served to anyone. The service is mocked so no real {@code gh} runs.
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
    void aLoggedInCallerGetsTheAccounts() throws Exception {
        when(service.accounts()).thenReturn(List.of(new GhAccount("haninaguib", true)));
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/api/github/accounts")
                        .session((MockHttpSession) loginResult.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].login").value("haninaguib"))
                .andExpect(jsonPath("$.accounts[0].active").value(true));
    }
}
