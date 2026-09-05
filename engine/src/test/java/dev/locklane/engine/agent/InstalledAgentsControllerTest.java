package dev.locklane.engine.agent;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Security filters are off here (like {@code UsageControllerTest}) — this slice checks routing/serialization, not auth. */
@WebMvcTest(InstalledAgentsController.class)
@AutoConfigureMockMvc(addFilters = false)
class InstalledAgentsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InstalledAgentsStore store;

    @Test
    void servesTheDetectedAgentsAsJsonInOrder() throws Exception {
        when(store.installed()).thenReturn(List.of(new AgentInfo("claude", "Claude"), new AgentInfo("opencode", "OpenCode")));

        mockMvc.perform(get("/api/agents/installed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed[0].id").value("claude"))
                .andExpect(jsonPath("$.installed[0].label").value("Claude"))
                .andExpect(jsonPath("$.installed[1].id").value("opencode"))
                .andExpect(jsonPath("$.installed[1].label").value("OpenCode"))
                .andExpect(jsonPath("$.installed.length()").value(2));
    }

    @Test
    void servesAnEmptyListWhenNothingWasDetected() throws Exception {
        when(store.installed()).thenReturn(List.of());

        mockMvc.perform(get("/api/agents/installed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed.length()").value(0));
    }
}
