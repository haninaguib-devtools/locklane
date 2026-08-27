package dev.locklane.engine.usage;

import java.util.Optional;

/** Reads one generic-password secret from the OS keychain, by service name. */
public interface KeychainReader {

    Optional<String> read(String service);
}
