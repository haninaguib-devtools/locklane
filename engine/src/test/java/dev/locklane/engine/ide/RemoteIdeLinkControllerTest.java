package dev.locklane.engine.ide;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.locklane.engine.persistence.IssueWorktreeService;
import dev.locklane.engine.persistence.ProjectIdeSessionService;
import dev.locklane.engine.pty.SessionRegistry;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Security filters are off here (like {@link InstalledIdesControllerTest}); the route's auth is proven by {@link InstalledIdesRouteIntegrationTest}'s matcher. */
@WebMvcTest(RemoteIdeLinkController.class)
@AutoConfigureMockMvc(addFilters = false)
class RemoteIdeLinkControllerTest {

    private static final Principal ALICE = () -> "alice";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IssueWorktreeService worktreeService;

    @MockitoBean
    private ProjectIdeSessionService projectIdeSessionService;

    @MockitoBean
    private SessionRegistry sessionRegistry;

    @Test
    void servesUserPortAndAbsolutePathForAVisibleSession() throws Exception {
        when(worktreeService.allWorktreeIds(1, "alice")).thenReturn(List.of("1-7-do-the-thing"));
        when(sessionRegistry.lastKnownWorkingDirectory("1-7-do-the-thing")).thenReturn(Optional.of(Path.of("/srv/wt/a")));

        mockMvc.perform(get("/api/ides/remote-link").param("project", "1").param("session", "1-7-do-the-thing").principal(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").value(System.getProperty("user.name")))
                .andExpect(jsonPath("$.sshPort").value(22))
                .andExpect(jsonPath("$.path").value("/srv/wt/a"))
                // The test classpath's application.yml sets no gateway keys, so these are the
                // @Value fallbacks; the shipped defaults are checked by shippedDefaultsNameAGatewayBackend.
                .andExpect(jsonPath("$.gateway.productCode").isEmpty())
                .andExpect(jsonPath("$.gateway.buildNumber").isEmpty())
                .andExpect(jsonPath("$.gateway.idePath").isEmpty());
    }

    @Test
    void coversAProjectsMainCheckoutIdeSessionThatIsOpenForTheCaller() throws Exception {
        when(worktreeService.allWorktreeIds(anyLong(), anyString())).thenReturn(List.of());
        when(projectIdeSessionService.isOpenAndVisibleTo(1, "1-ide-main", "alice")).thenReturn(true);
        when(sessionRegistry.lastKnownWorkingDirectory("1-ide-main")).thenReturn(Optional.of(Path.of("/srv/main")));

        mockMvc.perform(get("/api/ides/remote-link").param("project", "1").param("session", "1-ide-main").principal(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/srv/main"));
    }

    @Test
    void isNotFoundForASessionTheCallerMayNotSee() throws Exception {
        when(worktreeService.allWorktreeIds(anyLong(), anyString())).thenReturn(List.of());
        when(projectIdeSessionService.isOpenAndVisibleTo(anyLong(), anyString(), anyString())).thenReturn(false);
        when(sessionRegistry.lastKnownWorkingDirectory("2-9-bobs")).thenReturn(Optional.of(Path.of("/srv/wt/b")));

        mockMvc.perform(get("/api/ides/remote-link").param("project", "2").param("session", "2-9-bobs").principal(ALICE))
                .andExpect(status().isNotFound());
    }

    @Test
    void isNotFoundWhenTheSessionHasNoKnownWorkingDirectory() throws Exception {
        when(worktreeService.allWorktreeIds(1, "alice")).thenReturn(List.of("1-7-do-the-thing"));
        when(sessionRegistry.lastKnownWorkingDirectory("1-7-do-the-thing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/ides/remote-link").param("project", "1").param("session", "1-7-do-the-thing").principal(ALICE))
                .andExpect(status().isNotFound());
    }

    @Test
    void carriesTheConfiguredPortAndGatewaySettings() {
        IssueWorktreeService service = mock(IssueWorktreeService.class);
        when(service.allWorktreeIds(1, "alice")).thenReturn(List.of("1-7-x"));
        SessionRegistry registry = mock(SessionRegistry.class);
        when(registry.lastKnownWorkingDirectory("1-7-x")).thenReturn(Optional.of(Path.of("/srv/x")));
        RemoteIdeLinkController controller = new RemoteIdeLinkController(service, mock(ProjectIdeSessionService.class), registry,
                "hani", 2222, new RemoteIdeLinkController.Gateway("IU", "241.15989.150", null));

        var body = controller.remoteLink(1, "1-7-x", ALICE).getBody();

        org.assertj.core.api.Assertions.assertThat(body)
                .isEqualTo(new RemoteIdeLinkController.RemoteIdeLink("hani", 2222, "/srv/x",
                        new RemoteIdeLinkController.Gateway("IU", "241.15989.150", null)));
    }

    @Test
    void blankGatewaySettingsBecomeNull() {
        IssueWorktreeService service = mock(IssueWorktreeService.class);
        when(service.allWorktreeIds(1, "alice")).thenReturn(List.of("1-7-x"));
        SessionRegistry registry = mock(SessionRegistry.class);
        when(registry.lastKnownWorkingDirectory("1-7-x")).thenReturn(Optional.of(Path.of("/srv/x")));
        RemoteIdeLinkController controller = new RemoteIdeLinkController(service, mock(ProjectIdeSessionService.class), registry,
                22, " ", "", "");

        var body = controller.remoteLink(1, "1-7-x", ALICE).getBody();

        org.assertj.core.api.Assertions.assertThat(body.gateway())
                .isEqualTo(new RemoteIdeLinkController.Gateway(null, null, null));
    }

    /**
     * The shipped application.yml names a backend for Gateway to deploy (#971), so a
     * remote Gateway open works with no per-machine setup: the client refuses to build a
     * link when product code and build number are both blank and no ide-path is set.
     * Read from the source file because the test classpath's application.yml shadows it.
     */
    @Test
    void shippedDefaultsNameAGatewayBackend() {
        var factory = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        factory.setResources(new org.springframework.core.io.FileSystemResource("src/main/resources/application.yml"));
        java.util.Properties shipped = factory.getObject();

        org.assertj.core.api.Assertions.assertThat(shipped.getProperty("locklane.remote-ide.gateway.product-code")).isEqualTo("IU");
        org.assertj.core.api.Assertions.assertThat(shipped.getProperty("locklane.remote-ide.gateway.build-number")).isEqualTo("262.9437.185");
        org.assertj.core.api.Assertions.assertThat(shipped.getProperty("locklane.remote-ide.gateway.ide-path")).isEmpty();
    }
}
