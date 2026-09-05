package dev.locklane.engine.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

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
        ProviderUsage claude = new ProviderUsage(true, new WindowUsage(75.0, Instant.ofEpochSecond(1000)), null, List.of());
        when(usageService.snapshot()).thenReturn(new UsageSnapshot(List.of(
                new UsageSnapshot.ProviderSnapshot("claude", "Claude", "var(--green)", claude),
                new UsageSnapshot.ProviderSnapshot("codex", "Codex", "var(--amber)", ProviderUsage.unavailable()),
                new UsageSnapshot.ProviderSnapshot("opencode", "OpenCode", "#5f7ea6", ProviderUsage.unavailable())),
                Instant.ofEpochSecond(500)));

        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].id").value("claude"))
                .andExpect(jsonPath("$.providers[0].label").value("Claude"))
                .andExpect(jsonPath("$.providers[0].color").value("var(--green)"))
                .andExpect(jsonPath("$.providers[0].usage.available").value(true))
                .andExpect(jsonPath("$.providers[0].usage.fiveHour.percentLeft").value(75.0))
                .andExpect(jsonPath("$.providers[0].usage.weekly").doesNotExist())
                .andExpect(jsonPath("$.providers[1].id").value("codex"))
                .andExpect(jsonPath("$.providers[1].usage.available").value(false))
                .andExpect(jsonPath("$.providers[2].id").value("opencode"))
                .andExpect(jsonPath("$.providers[2].usage.available").value(false))
                .andExpect(jsonPath("$.providers.length()").value(3));
    }
}
