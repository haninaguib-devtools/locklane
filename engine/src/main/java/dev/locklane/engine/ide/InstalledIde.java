package dev.locklane.engine.ide;

import java.util.Objects;

/**
 * One IDE {@link InstalledIdeDetector} found on this host (#781): its table row, plus
 * — on macOS, when it was found as an app bundle under an Applications folder rather
 * than as a {@code PATH} executable — the bundle's name without {@code .app}, which is
 * what {@code open -a} takes. {@code null} everywhere else.
 */
public record InstalledIde(IdeInfo info, String macApp) {

    public InstalledIde {
        Objects.requireNonNull(info, "info");
    }

    static InstalledIde onPath(IdeInfo info) {
        return new InstalledIde(info, null);
    }

    public String id() {
        return info.id();
    }
}
