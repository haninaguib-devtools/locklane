package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Claude Code's own OAuth access token (#137's Goal), read the same two ways the CLI
 * itself can store it: the JSON credentials file first, the macOS Keychain as a
 * fallback (the file is what `claude` writes on Linux and when Keychain storage isn't
 * used on macOS either). An expired token is not detected here — it is simply passed
 * along and left to fail the upstream call, which {@link ClaudeUsageProvider} turns
 * into "unavailable" (#137's Non-goals: no refreshing).
 */
public class ClaudeTokenSource {

    private static final String KEYCHAIN_SERVICE = "Claude Code-credentials";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path credentialsFile;
    private final KeychainReader keychainReader;

    public ClaudeTokenSource(Path credentialsFile, KeychainReader keychainReader) {
        this.credentialsFile = credentialsFile;
        this.keychainReader = keychainReader;
    }

    public static ClaudeTokenSource forCurrentUser() {
        return new ClaudeTokenSource(
                Path.of(System.getProperty("user.home"), ".claude", ".credentials.json"),
                new MacKeychainReader());
    }

    public Optional<String> accessToken() {
        return readFromFile().or(() -> keychainReader.read(KEYCHAIN_SERVICE).flatMap(this::tokenFromJson));
    }

    private Optional<String> readFromFile() {
        if (!Files.isReadable(credentialsFile)) {
            return Optional.empty();
        }
        try {
            return tokenFromJson(Files.readString(credentialsFile));
        } catch (IOException e) {
            // silent: the Keychain fallback above is next; a read failure here is
            // routine (permissions, a transient race) and never load-bearing on its
            // own.
            return Optional.empty();
        }
    }

    private Optional<String> tokenFromJson(String json) {
        try {
            JsonNode token = MAPPER.readTree(json).path("claudeAiOauth").path("accessToken");
            return token.isTextual() && !token.asText().isBlank() ? Optional.of(token.asText()) : Optional.empty();
        } catch (IOException e) {
            // silent: malformed/unexpected JSON reads as "no token" — see this
            // class's own doc on ClaudeUsageProvider degrading gracefully.
            return Optional.empty();
        }
    }
}
