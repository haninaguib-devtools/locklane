package dev.locklane.engine.ide;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #781's "authenticated endpoint" done-when over the real security filter chain, the
 * way {@code TemplatesRouteIntegrationTest} proves {@code /api/templates}: {@code
 * SecurityConfig} ends in {@code permitAll}, so this is the test that proves the
 * {@code /api/ides/**} matcher exists. The real boot probe runs against this host, so
 * only the shape of a logged-in answer is asserted, never which IDEs it lists.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstalledIdesRouteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anUnauthenticatedCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/ides/installed")).andExpect(status().isUnauthorized());
    }

    @Test
    void aLoggedInCallerGetsTheInstalledList() throws Exception {
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/api/ides/installed")
                        .session((MockHttpSession) loginResult.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").isArray());
    }
}
