package dev.locklane.engine.ide;

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

/** Security filters are off here (like {@code InstalledAgentsControllerTest}) — this slice checks routing/serialization, not auth. */
@WebMvcTest(InstalledIdesController.class)
@AutoConfigureMockMvc(addFilters = false)
class InstalledIdesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InstalledIdesStore store;

    @Test
    void servesTheDetectedIdesAsJsonInOrderWithTheirDesktopFlag() throws Exception {
        when(store.installed()).thenReturn(List.of(new IdeInfo("code-server", "code-server", false),
                new IdeInfo("intellij", "IntelliJ IDEA", true)));

        mockMvc.perform(get("/api/ides/installed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed[0].id").value("code-server"))
                .andExpect(jsonPath("$.installed[0].label").value("code-server"))
                .andExpect(jsonPath("$.installed[0].desktop").value(false))
                .andExpect(jsonPath("$.installed[1].id").value("intellij"))
                .andExpect(jsonPath("$.installed[1].label").value("IntelliJ IDEA"))
                .andExpect(jsonPath("$.installed[1].desktop").value(true))
                .andExpect(jsonPath("$.installed.length()").value(2));
    }

    @Test
    void servesAnEmptyListWhenNothingWasDetected() throws Exception {
        when(store.installed()).thenReturn(List.of());

        mockMvc.perform(get("/api/ides/installed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed.length()").value(0));
    }
}
