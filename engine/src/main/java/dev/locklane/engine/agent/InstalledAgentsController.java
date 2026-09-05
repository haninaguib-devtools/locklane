package dev.locklane.engine.agent;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Settings dialog's "Default agent" picker (#359, #695) which CLIs were
 * found on the host `PATH` at startup, id and display label both, so the client can
 * render a button only for one that is actually installed without knowing any agent's
 * name itself.
 */
@RestController
@RequestMapping("/api/agents")
public class InstalledAgentsController {

    private final InstalledAgentsStore store;

    public InstalledAgentsController(InstalledAgentsStore store) {
        this.store = store;
    }

    @GetMapping("/installed")
    public InstalledAgentsResponse installed() {
        return new InstalledAgentsResponse(store.installed());
    }

    record InstalledAgentsResponse(List<AgentInfo> installed) {
    }
}
