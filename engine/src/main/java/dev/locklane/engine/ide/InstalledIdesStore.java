package dev.locklane.engine.ide;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Which rows of {@link KnownIdes#TABLE} {@link InstalledIdesBootstrapper} found on
 * this host at startup (#781), the way {@code InstalledAgentsStore} holds the detected
 * agent CLIs. Detection runs once at boot; this store never re-probes, so its installed
 * list is fixed for the process's lifetime once {@link #set} has run. Empty until then,
 * which means {@code open-ide} answers 400 for every desktop id until the probe has run
 * — a window of milliseconds at startup.
 */
@Component
public class InstalledIdesStore {

    /** The id of the bundled, browser-served IDE — {@code open-ide}'s default when the body names none. */
    public static final String CODE_SERVER_ID = KnownIdes.CODE_SERVER.info().id();

    private volatile List<InstalledIde> installed = List.of();

    void set(List<InstalledIde> found) {
        this.installed = List.copyOf(found);
    }

    /** What {@code GET /api/ides/installed} serves, in table order. */
    public List<IdeInfo> installed() {
        return installed.stream().map(InstalledIde::info).toList();
    }

    /** The detected entry for {@code id}, or empty when {@code id} is unknown or was not found on this host. */
    public Optional<InstalledIde> find(String id) {
        return installed.stream().filter(ide -> ide.id().equals(id)).findFirst();
    }
}
