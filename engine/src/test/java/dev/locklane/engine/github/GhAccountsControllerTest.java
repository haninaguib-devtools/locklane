package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security filters are off here (like {@code InstalledAgentsControllerTest}) — this
 * slice checks routing and the JSON shape; {@link GhAccountsRouteIntegrationTest}
 * covers the authentication gate over the real filter chain.
 */
@WebMvcTest(GhAccountsController.class)
@AutoConfigureMockMvc(addFilters = false)
class GhAccountsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GhAccountsService service;

    @Test
    void servesTheAccountsAsJsonInOrderWithTheActiveFlag() throws Exception {
        when(service.accounts()).thenReturn(List.of(
                new GhAccount("haninaguib", true), new GhAccount("hani-thyme", false)));

        mockMvc.perform(get("/api/github/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].login").value("haninaguib"))
                .andExpect(jsonPath("$.accounts[0].active").value(true))
                .andExpect(jsonPath("$.accounts[1].login").value("hani-thyme"))
                .andExpect(jsonPath("$.accounts[1].active").value(false));
    }

    @Test
    void servesAnEmptyListWhenThereAreNoAccounts() throws Exception {
        when(service.accounts()).thenReturn(List.of());

        mockMvc.perform(get("/api/github/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(0));
    }
}
