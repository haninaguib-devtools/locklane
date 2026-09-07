package dev.locklane.engine.ide;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The single table of IDEs the engine knows how to detect and launch (#781) — the only
 * place id, label, whether it is a desktop IDE, and each OS's detection target and
 * launch command are written down. {@link InstalledIdeDetector} walks it at boot,
 * {@link InstalledIdesStore} serves it in this order, and {@link DesktopIdeLauncher}
 * builds its command from it through {@link #launchCommand}.
 *
 * <p>The bundled code-server row has no executable of its own here: it is detected by
 * the binary {@code CodeServerService} resolves under the data dir, and it is not a
 * desktop IDE — {@code open-ide} without an id, or with this id, keeps starting it and
 * proxying it exactly as before this table existed.
 */
final class KnownIdes {

    /**
     * One row: the executable looked up on {@code PATH} on Linux ({@code code}),
     * Windows ({@code code.cmd}, launched via {@code cmd /c}; {@code idea64.exe}) and
     * macOS ({@code code}), plus the macOS app-bundle name — a glob, since JetBrains
     * ships several editions ({@code IntelliJ IDEA*.app}) — looked for directly under
     * {@code /Applications} and {@code ~/Applications}. All {@code null} on the
     * code-server row.
     */
    record Definition(IdeInfo info, String linuxExecutable, String windowsExecutable, String macExecutable,
            String macAppGlob) {
    }

    static final Definition CODE_SERVER = new Definition(new IdeInfo("code-server", "code-server", false),
            null, null, null, null);

    static final List<Definition> TABLE = List.of(
            CODE_SERVER,
            new Definition(new IdeInfo("vscode", "VS Code", true), "code", "code.cmd", "code",
                    "Visual Studio Code.app"),
            new Definition(new IdeInfo("intellij", "IntelliJ IDEA", true), "idea", "idea64.exe", "idea",
                    "IntelliJ IDEA*.app"));

    private KnownIdes() {
    }

    static Optional<Definition> find(String id) {
        return TABLE.stream().filter(definition -> definition.info().id().equals(id)).findFirst();
    }

    /**
     * The command that opens {@code directory} in {@code ide} on {@code os}: {@code code
     * <dir>} / {@code idea <dir>} on Linux; on Windows the same, wrapped in {@code cmd /c}
     * when the executable is a {@code .cmd} script (a script is not directly spawnable);
     * on macOS {@code open -a "<App name>" <dir>} when the IDE was found as an app
     * bundle, else its {@code PATH} shim. Refuses the code-server row, which is never
     * launched this way.
     */
    static String[] launchCommand(HostOs os, InstalledIde ide, Path directory) {
        Definition definition = find(ide.id())
                .orElseThrow(() -> new IllegalArgumentException("unknown IDE id: " + ide.id()));
        if (!definition.info().desktop()) {
            throw new IllegalArgumentException(ide.id() + " is not a desktop IDE");
        }
        String dir = directory.toString();
        return switch (os) {
            case LINUX -> new String[] {definition.linuxExecutable(), dir};
            case WINDOWS -> definition.windowsExecutable().endsWith(".cmd")
                    ? new String[] {"cmd", "/c", definition.windowsExecutable(), dir}
                    : new String[] {definition.windowsExecutable(), dir};
            case MAC -> ide.macApp() != null
                    ? new String[] {"open", "-a", ide.macApp(), dir}
                    : new String[] {definition.macExecutable(), dir};
        };
    }
}
