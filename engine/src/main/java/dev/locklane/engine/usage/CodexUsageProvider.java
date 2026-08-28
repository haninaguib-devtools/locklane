package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Codex's usage, from the same undocumented backend the CLI's own status screen reads
 * — found by inspecting the installed {@code codex} binary for #137 (OpenAI does not
 * publish it either): a {@code wham/usage} endpoint behind ChatGPT's backend API.
 * Verified against a live account: the windows sit under a singular {@code rate_limit}
 * object as {@code primary_window} (Codex's 5-hour-scale window) and
 * {@code secondary_window} (its weekly-scale window), each carrying {@code used_percent}
 * and a Unix-epoch-seconds {@code reset_at} — mirroring Claude's five_hour/seven_day
 * pair for this widget's purposes. Any mismatch fails to parse and degrades to
 * {@link ProviderUsage#unavailable()}, same as a real outage (#137's Goal).
 */
public class CodexUsageProvider implements UsageProvider {

    static final String ENDPOINT = "https://chatgpt.com/backend-api/wham/usage";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CodexTokenSource tokenSource;
    private final UsageHttpClient httpClient;

    public CodexUsageProvider(CodexTokenSource tokenSource, UsageHttpClient httpClient) {
        this.tokenSource = tokenSource;
        this.httpClient = httpClient;
    }

    @Override
    public ProviderUsage fetch() {
        Optional<CodexCredentials> credentials = tokenSource.credentials();
        if (credentials.isEmpty()) {
            return ProviderUsage.unavailable();
        }
        CodexCredentials creds = credentials.get();
        Optional<String> body = httpClient.get(ENDPOINT, Map.of(
                "Authorization", "Bearer " + creds.accessToken(),
                "chatgpt-account-id", creds.accountId(),
                "Content-Type", "application/json"));
        return body.flatMap(this::parse).orElseGet(ProviderUsage::unavailable);
    }

    private Optional<ProviderUsage> parse(String json) {
        try {
            JsonNode rateLimit = MAPPER.readTree(json).path("rate_limit");
            WindowUsage primary = window(rateLimit.path("primary_window"));
            WindowUsage secondary = window(rateLimit.path("secondary_window"));
            if (primary == null && secondary == null) {
                return Optional.empty();
            }
            return Optional.of(new ProviderUsage(true, primary, secondary, List.of()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static WindowUsage window(JsonNode node) {
        JsonNode usedPercent = node.path("used_percent");
        JsonNode resetAt = node.path("reset_at");
        if (!usedPercent.isNumber() || !resetAt.isNumber()) {
            return null;
        }
        double percentLeft = Math.max(0, 100 - usedPercent.asDouble());
        return new WindowUsage(percentLeft, Instant.ofEpochSecond(resetAt.asLong()));
    }
}
