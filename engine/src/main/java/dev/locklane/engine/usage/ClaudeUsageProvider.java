package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Claude Code's usage, from the same undocumented endpoint the CLI's own status line
 * calls ({@code fetchUtilization}) to show "N% left" — found by inspecting the
 * installed {@code claude} binary for #137, since Anthropic does not publish it. Its
 * response shape (a {@code rate_limits} object with {@code five_hour}/{@code seven_day}
 * windows, each carrying {@code used_percentage} and a Unix-epoch-seconds
 * {@code resets_at}) is the same shape documented inside the CLI for its statusline
 * hook's JSON input, which this data ultimately feeds. If Anthropic changes either the
 * endpoint or this shape, every response fails to parse and this provider degrades to
 * {@link ProviderUsage#unavailable()} — never a broken sidebar (#137's Goal).
 */
public class ClaudeUsageProvider implements UsageProvider {

    static final String ENDPOINT = "https://api.anthropic.com/api/oauth/usage";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClaudeTokenSource tokenSource;
    private final UsageHttpClient httpClient;

    public ClaudeUsageProvider(ClaudeTokenSource tokenSource, UsageHttpClient httpClient) {
        this.tokenSource = tokenSource;
        this.httpClient = httpClient;
    }

    @Override
    public ProviderUsage fetch() {
        Optional<String> token = tokenSource.accessToken();
        if (token.isEmpty()) {
            return ProviderUsage.unavailable();
        }
        Optional<String> body = httpClient.get(ENDPOINT, Map.of(
                "Authorization", "Bearer " + token.get(),
                "Content-Type", "application/json"));
        return body.flatMap(this::parse).orElseGet(ProviderUsage::unavailable);
    }

    private Optional<ProviderUsage> parse(String json) {
        try {
            JsonNode rateLimits = MAPPER.readTree(json).path("rate_limits");
            WindowUsage fiveHour = window(rateLimits.path("five_hour"));
            WindowUsage weekly = window(rateLimits.path("seven_day"));
            if (fiveHour == null && weekly == null) {
                return Optional.empty();
            }
            return Optional.of(new ProviderUsage(true, fiveHour, weekly));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static WindowUsage window(JsonNode node) {
        JsonNode usedPercentage = node.path("used_percentage");
        JsonNode resetsAt = node.path("resets_at");
        if (!usedPercentage.isNumber() || !resetsAt.isNumber()) {
            return null;
        }
        double percentLeft = Math.max(0, 100 - usedPercentage.asDouble());
        return new WindowUsage(percentLeft, Instant.ofEpochSecond(resetsAt.asLong()));
    }
}
