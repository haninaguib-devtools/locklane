package dev.locklane.engine.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Shells out to {@code security find-generic-password}, the same macOS Keychain that
 * Claude Code itself writes to under the service name {@code "Claude Code-credentials"}
 * when it is configured to store its OAuth token there instead of
 * {@code ~/.claude/.credentials.json} (#137's Goal). A no-op on any other OS, and on
 * any failure (item not found, denied access, {@code security} missing) — read-only,
 * best-effort, same as the file path.
 */
public class MacKeychainReader implements KeychainReader {

    private static final Logger log = LoggerFactory.getLogger(MacKeychainReader.class);

    @Override
    public Optional<String> read(String service) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return Optional.empty();
        }
        try {
            Process process = new ProcessBuilder("security", "find-generic-password", "-s", service, "-w")
                    .redirectErrorStream(false)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int exit = process.waitFor();
            return exit == 0 && !output.isEmpty() ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            log.debug("Could not run `security find-generic-password` for service {}", service, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while running `security find-generic-password` for service {}", service, e);
            return Optional.empty();
        }
    }
}
