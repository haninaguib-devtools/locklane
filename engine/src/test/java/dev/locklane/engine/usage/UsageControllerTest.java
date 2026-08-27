package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Security filters are off here (like {@code IssueControllerRoutingTest}) — this slice checks routing/serialization, not auth. */
@WebMvcTest(UsageController.class)
@AutoConfigureMockMvc(addFilters = false)
class UsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UsageService usageService;

    @Test
    void servesTheSnapshotAsJson() throws Exception {
        ProviderUsage claude = new ProviderUsage(true, new WindowUsage(75.0, Instant.ofEpochSecond(1000)), null);
        when(usageService.snapshot()).thenReturn(new UsageSnapshot(claude, ProviderUsage.unavailable(), Instant.ofEpochSecond(500)));

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claude.available").value(true))
                .andExpect(jsonPath("$.claude.fiveHour.percentLeft").value(75.0))
                .andExpect(jsonPath("$.claude.weekly").doesNotExist())
                .andExpect(jsonPath("$.codex.available").value(false));
    }
}
