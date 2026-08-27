package dev.locklane.engine.usage;

import java.util.Map;
import java.util.Optional;

/**
 * A single authenticated GET, abstracted so {@link ClaudeUsageProvider} and
 * {@link CodexUsageProvider} can be tested without a real network call. Empty means
 * "could not get a usable response" (network error, timeout, non-2xx status) — the
 * providers turn that into their own "unavailable" state, never an exception.
 */
public interface UsageHttpClient {

    Optional<String> get(String url, Map<String, String> headers);
}
