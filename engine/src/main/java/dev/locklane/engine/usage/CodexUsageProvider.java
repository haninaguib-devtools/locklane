package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Codex's usage, from the same undocumented backend the CLI's own status screen reads
 * — found by inspecting the installed {@code codex} binary for #137 (OpenAI does not
 * publish it either): a {@code wham/usage} endpoint behind ChatGPT's backend API, and
 * response fields matching the CLI's own {@code RateLimitWindow} shape
 * ({@code used_percent}, {@code resets_at}) under {@code primary} (Codex's 5-hour-scale
 * window) and {@code secondary} (its weekly-scale window) — mirroring Claude's
 * five_hour/seven_day pair for this widget's purposes. Less certain than Claude's shape
 * since Codex has no equivalent public documentation to cross-check against; any
 * mismatch fails to parse and degrades to {@link ProviderUsage#unavailable()}, same as
 * a real outage (#137's Goal).
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
            JsonNode root = MAPPER.readTree(json);
            // The rate limit windows may sit at the response's top level or nested
            // under "rate_limits" — tried in that order since the exact shape is
            // unconfirmed (see class doc).
            JsonNode windows = root.has("primary") || root.has("secondary") ? root : root.path("rate_limits");
            WindowUsage primary = window(windows.path("primary"));
            WindowUsage secondary = window(windows.path("secondary"));
            if (primary == null && secondary == null) {
                return Optional.empty();
            }
            return Optional.of(new ProviderUsage(true, primary, secondary));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static WindowUsage window(JsonNode node) {
        JsonNode usedPercent = node.path("used_percent");
        JsonNode resetsAt = node.path("resets_at");
        if (!usedPercent.isNumber() || !resetsAt.isNumber()) {
            return null;
        }
        double percentLeft = Math.max(0, 100 - usedPercent.asDouble());
        return new WindowUsage(percentLeft, Instant.ofEpochSecond(resetsAt.asLong()));
    }
}
