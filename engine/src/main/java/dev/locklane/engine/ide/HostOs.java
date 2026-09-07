package dev.locklane.engine.ide;

import java.util.Locale;

/**
 * The three operating systems {@link KnownIdes}' table tells apart, resolved from the
 * {@code os.name} system property the same way {@code FileManagerLauncher} does:
 * {@code mac} in the name is macOS, {@code win} is Windows, and everything else is
 * treated as Linux.
 */
public enum HostOs {
    LINUX, MAC, WINDOWS;

    public static HostOs of(String osName) {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac")) {
            return MAC;
        }
        if (lower.contains("win")) {
            return WINDOWS;
        }
        return LINUX;
    }

    public static HostOs current() {
        return of(System.getProperty("os.name", ""));
    }
}
