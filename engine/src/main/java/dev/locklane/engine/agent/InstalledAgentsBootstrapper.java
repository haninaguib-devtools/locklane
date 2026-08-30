package dev.locklane.engine.agent;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Probes the host `PATH` once at startup (#359) for each currently-supported CLI —
 * `claude`, `codex`, `opencode`, the same names {@code ResumeIdScanner} and
 * {@code TerminalWebSocketHandler} already use — so the Settings dialog's
 * "Default agent" picker (served from {@link InstalledAgentsController}) only offers
 * one a caller can actually launch.
 */
@Component
public class InstalledAgentsBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstalledAgentsBootstrapper.class);

    private final InstalledAgentsStore store;

    public InstalledAgentsBootstrapper(InstalledAgentsStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> found = InstalledAgentDetector.detect(System.getenv("PATH"), InstalledAgentsStore.KNOWN_AGENTS);
        store.set(found);
        log.info("Detected installed agent CLIs on PATH: {}", found);
    }
}
