package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * OpenCode's own stored credentials (#295), read from {@code
 * ~/.local/share/opencode/auth.json} — confirmed as the CLI's real credentials path via
 * {@code opencode auth list}, which prints that exact path. That file stores one entry
 * per connected provider, keyed by provider id; the {@code "opencode"} key is assumed to
 * hold the CLI's own account (used for OpenCode Zen), the way {@code "anthropic"} or
 * {@code "openai"} would hold a BYOK provider's — unconfirmed against a live account, so
 * a future correction may be needed here without touching any other class. Same
 * no-refresh stance as {@link ClaudeTokenSource}/{@link CodexTokenSource}.
 */
public class OpenCodeTokenSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path authFile;

    public OpenCodeTokenSource(Path authFile) {
        this.authFile = authFile;
    }

    public static OpenCodeTokenSource forCurrentUser() {
        return new OpenCodeTokenSource(
                Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "auth.json"));
    }

    public Optional<OpenCodeCredentials> credentials() {
        if (!Files.isReadable(authFile)) {
            return Optional.empty();
        }
        try {
            JsonNode access = MAPPER.readTree(Files.readString(authFile)).path("opencode").path("access");
            return access.isTextual() && !access.asText().isBlank()
                    ? Optional.of(new OpenCodeCredentials(access.asText()))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
