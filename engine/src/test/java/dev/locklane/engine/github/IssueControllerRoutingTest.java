package dev.locklane.engine.github;

import dev.locklane.engine.ws.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms the real Spring routing decision for #21's new endpoint: the literal
 * "/tree" segment must resolve to {@link IssueController#tree}, not be swallowed
 * by the "/{number}" path variable (which would otherwise fail to parse "tree" as
 * an int). Worth testing directly rather than assuming, since the two mappings sit
 * on the same controller under the same prefix. Security filters are switched off
 * (#47) — this slice never loads the app's own {@code SecurityConfig}, so without
 * that it would fall back to Spring Boot's auto-configured default-secure chain,
 * which is irrelevant to what this test is checking.
 *
 * <p>Since #81, {@link IssueController} resolves everything through
 * {@link ProjectGhResources} rather than injecting {@code GhIssueCache} et al.
 * directly (they are no longer Spring beans, built per project instead) — so that,
 * plus {@link EventBroadcaster} (a plain {@code @Component} a {@code @WebMvcTest}
 * slice does not pull in on its own), is what this slice mocks, with real
 * per-project services wired to a {@link FixedGhClient} behind it.
 */
@WebMvcTest(IssueController.class)
@AutoConfigureMockMvc(addFilters = false)
class IssueControllerRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectGhResources resources;

    @MockitoBean
    private EventBroadcaster eventBroadcaster;

    @Test
    void treeRouteResolvesToTheTreeEndpoint() throws Exception {
        GhIssueCache cache = new GhIssueCache(
                new FixedGhClient(List.of(new GhIssue(1, "Initiative", "OPEN", List.of("initiative"), "", "", ""))));
        when(resources.forProject(1L)).thenReturn(
                Optional.of(new ProjectGhContext(null, cache, null, new IssueTreeService(cache))));

        mockMvc.perform(get("/api/projects/1/issues/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[0].kind").value("INITIATIVE"));
    }

    @Test
    void numericPathStillResolvesToTheSingleIssueLookup() throws Exception {
        GhIssueCache cache = new GhIssueCache(
                new FixedGhClient(List.of(new GhIssue(5, "Five", "OPEN", List.of(), "", "", ""))));
        when(resources.forProject(1L)).thenReturn(Optional.of(new ProjectGhContext(null, cache, null, null)));

        mockMvc.perform(get("/api/projects/1/issues/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(5));
    }

    @Test
    void anUnknownProjectIsNotFound() throws Exception {
        when(resources.forProject(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/projects/999/issues/5")).andExpect(status().isNotFound());
    }

    private static final class FixedGhClient implements GhClient {
        private final List<GhIssue> issues;

        FixedGhClient(List<GhIssue> issues) {
            this.issues = issues;
        }

        @Override
        public List<GhIssue> issues() {
            return issues;
        }

        @Override
        public List<GhPullRequest> pullRequests() {
            return List.of();
        }

        @Override
        public Optional<GhPullRequestDetail> pullRequestDetail(int number) {
            return Optional.empty();
        }
    }
}
