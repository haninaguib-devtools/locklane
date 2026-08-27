package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Codex's own ChatGPT OAuth credentials (#137's Goal), read from {@code ~/.codex/auth.json}
 * — the only place the CLI stores them; unlike Claude Code there is no keychain option
 * here. An expired token is left to fail the upstream call rather than detected here,
 * same as {@link ClaudeTokenSource} (#137's Non-goals: no refreshing).
 */
public class CodexTokenSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path authFile;

    public CodexTokenSource(Path authFile) {
        this.authFile = authFile;
    }

    public static CodexTokenSource forCurrentUser() {
        return new CodexTokenSource(Path.of(System.getProperty("user.home"), ".codex", "auth.json"));
    }

    public Optional<CodexCredentials> credentials() {
        if (!Files.isReadable(authFile)) {
            return Optional.empty();
        }
        try {
            JsonNode tokens = MAPPER.readTree(Files.readString(authFile)).path("tokens");
            JsonNode accessToken = tokens.path("access_token");
            JsonNode accountId = tokens.path("account_id");
            if (!accessToken.isTextual() || accessToken.asText().isBlank()
                    || !accountId.isTextual() || accountId.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new CodexCredentials(accessToken.asText(), accountId.asText()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
