package dev.locklane.engine.persistence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #784's "listed in {@code SecurityConfig}" done-when over the real security filter
 * chain, the way {@code InstalledIdesRouteIntegrationTest} proves {@code
 * /api/ides/installed}: {@code SecurityConfig} ends in {@code permitAll}, so this is the
 * test that proves the {@code /api/projects/*}{@code /consoles/*}{@code
 * /reveal-in-file-manager} matcher exists — without it an anonymous call reached the
 * controller and failed with a 500 on a null principal (#655's record). The logged-in
 * case here only shows the matcher lets an authenticated caller through to the
 * controller's own answer; the controller's rules themselves — owner-only visibility,
 * the loopback gate — are {@code AgentSessionsControllerTest}'s.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevealInFileManagerRouteIntegrationTest {
    // Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories and the
    // /console and /consoles REST paths below keep their persisted and on-the-wire shape: compatibility
    // surfaces kept under ADR-112 (#766 renamed only the identifiers).

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anUnauthenticatedCallerIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/projects/1/consoles/1-174-rename-toggle/reveal-in-file-manager"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aLoggedInCallerReachesTheController() throws Exception {
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk())
                .andReturn();

        // No such project or agent session for this account: the controller's own 404, not
        // the filter chain's 401 -- which is all this test is about.
        mockMvc.perform(post("/api/projects/1/consoles/1-174-rename-toggle/reveal-in-file-manager")
                        .session((MockHttpSession) loginResult.getRequest().getSession(false)))
                .andExpect(status().isNotFound());
    }
}
