package dev.locklane.engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A direct load of /issues/:id (#31) has no server-side mapping of its own --
 * confirms it forwards to index.html instead of 404ing.
 */
@WebMvcTest(SpaFallbackController.class)
@AutoConfigureMockMvc(addFilters = false)
class SpaFallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void issueRouteForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/issues/31"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }
}
