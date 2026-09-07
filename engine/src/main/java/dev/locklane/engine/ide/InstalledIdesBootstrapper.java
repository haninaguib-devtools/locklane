package dev.locklane.engine.ide;

import dev.locklane.engine.codeserver.CodeServerService;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Probes this host once at startup (#781) for each IDE in {@link KnownIdes#TABLE} —
 * the bundled code-server binary {@link CodeServerService} resolves under the data
 * dir, and VS Code / IntelliJ IDEA on {@code PATH} or, on macOS, under
 * {@code /Applications} and {@code ~/Applications} — so the Settings dialog's "IDE"
 * picker (served from {@link InstalledIdesController}) only offers what {@code
 * open-ide} can actually launch. The twin of {@code InstalledAgentsBootstrapper}.
 */
@Component
public class InstalledIdesBootstrapper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstalledIdesBootstrapper.class);

    private final InstalledIdesStore store;
    private final CodeServerService codeServerService;

    public InstalledIdesBootstrapper(InstalledIdesStore store, CodeServerService codeServerService) {
        this.store = store;
        this.codeServerService = codeServerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Path> applicationDirs = List.of(Path.of("/Applications"),
                Path.of(System.getProperty("user.home", ""), "Applications"));
        List<InstalledIde> found = InstalledIdeDetector.detect(HostOs.current(), System.getenv("PATH"),
                codeServerService.binary(), applicationDirs);
        store.set(found);
        log.info("Detected installed IDEs: {}", found.stream().map(InstalledIde::id).toList());
    }
}
