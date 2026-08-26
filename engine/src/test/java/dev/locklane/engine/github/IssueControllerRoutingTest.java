package dev.locklane.engine.github;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms the real Spring routing decision for #21's new endpoint: the literal
 * "/tree" segment must resolve to {@link IssueController#tree()}, not be swallowed
 * by the "/{number}" path variable (which would otherwise fail to parse "tree" as
 * an int). Worth testing directly rather than assuming, since the two mappings sit
 * on the same controller under the same prefix. Security filters are switched off
 * (#47) — this slice never loads the app's own {@code SecurityConfig}, so without
 * that it would fall back to Spring Boot's auto-configured default-secure chain,
 * which is irrelevant to what this test is checking.
 */
@WebMvcTest(IssueController.class)
@AutoConfigureMockMvc(addFilters = false)
class IssueControllerRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GhIssueCache cache;

    @MockitoBean
    private IssueDetailService detailService;

    @MockitoBean
    private IssueTreeService treeService;

    @Test
    void treeRouteResolvesToTheTreeEndpoint() throws Exception {
        when(treeService.tree()).thenReturn(
                List.of(new TreeNode(1, "Initiative", "INITIATIVE", "OPEN", List.of())));

        mockMvc.perform(get("/api/projects/1/issues/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[0].kind").value("INITIATIVE"));
    }

    @Test
    void numericPathStillResolvesToTheSingleIssueLookup() throws Exception {
        when(cache.issue(eq(5))).thenReturn(
                Optional.of(new GhIssue(5, "Five", "OPEN", List.of(), "", "", "")));

        mockMvc.perform(get("/api/projects/1/issues/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(5));
    }
}
