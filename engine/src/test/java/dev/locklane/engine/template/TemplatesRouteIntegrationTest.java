package dev.locklane.engine.template;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #536's "authenticated endpoint" done-when over the real security filter chain:
 * {@code SecurityConfig} ends in {@code permitAll}, so this is the test that proves the
 * {@code /api/templates} matcher exists — without it the host's template list would be
 * served to anyone. The real store runs, over the test data-dir (no host templates) plus
 * the classpath built-ins.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TemplatesRouteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anUnauthenticatedCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/templates")).andExpect(status().isUnauthorized());
    }

    @Test
    void aLoggedInCallerGetsTheTemplates() throws Exception {
        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", "test-user")
                        .param("password", "test-password"))
                .andExpect(status().isOk())
                .andReturn();

        mockMvc.perform(get("/api/templates")
                        .session((MockHttpSession) loginResult.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[?(@.name == 'springboot-angular')].title")
                        .value("Spring Boot + Angular"));
    }
}
