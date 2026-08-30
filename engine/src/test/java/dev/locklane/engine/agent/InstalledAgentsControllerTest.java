package dev.locklane.engine.agent;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashSet;
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
        when(store.installed()).thenReturn(new LinkedHashSet<>(java.util.List.of("claude", "opencode")));

        mockMvc.perform(get("/api/agents/installed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed[0]").value("claude"))
                .andExpect(jsonPath("$.installed[1]").value("opencode"))
                .andExpect(jsonPath("$.installed.length()").value(2));
    }

    @Test
    void servesAnEmptyListWhenNothingWasDetected() throws Exception {
        when(store.installed()).thenReturn(java.util.Set.of());

        mockMvc.perform(get("/api/agents/installed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed.length()").value(0));
    }
}
