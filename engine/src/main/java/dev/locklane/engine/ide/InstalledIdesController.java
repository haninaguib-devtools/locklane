package dev.locklane.engine.ide;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Settings dialog's "IDE" picker (#781) which IDEs were found on this host
 * at startup — id, display label and whether it is a desktop IDE — in
 * {@link KnownIdes#TABLE} order, so the client renders a choice only for one {@code
 * open-ide} can actually launch, without knowing any IDE's name itself. Gated behind
 * authentication in {@code SecurityConfig} like {@code /api/agents/**}; the client
 * itself hides desktop entries from a browser that is not on the engine's own machine,
 * and the engine refuses them regardless ({@code security.LoopbackRequests}).
 */
@RestController
@RequestMapping("/api/ides")
public class InstalledIdesController {

    private final InstalledIdesStore store;

    public InstalledIdesController(InstalledIdesStore store) {
        this.store = store;
    }

    @GetMapping("/installed")
    public InstalledIdesResponse installed() {
        return new InstalledIdesResponse(store.installed());
    }

    record InstalledIdesResponse(List<IdeInfo> installed) {
    }
}
