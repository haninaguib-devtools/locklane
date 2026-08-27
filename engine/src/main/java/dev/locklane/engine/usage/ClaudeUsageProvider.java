package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * Claude Code's usage, from the same undocumented endpoint the CLI's own status line
 * calls ({@code fetchUtilization}) to show "N% left" — found by inspecting the
 * installed {@code claude} binary for #137, since Anthropic does not publish it.
 * Verified against a live account: the {@code five_hour}/{@code seven_day} windows sit
 * at the response's top level — not nested under {@code rate_limits}, despite that
 * being the shape documented inside the CLI for its unrelated statusline-hook JSON
 * input — each carrying a {@code utilization} percentage and an ISO-8601
 * {@code resets_at} timestamp (not Unix-epoch seconds). If Anthropic changes either the
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
            JsonNode root = MAPPER.readTree(json);
            WindowUsage fiveHour = window(root.path("five_hour"));
            WindowUsage weekly = window(root.path("seven_day"));
            if (fiveHour == null && weekly == null) {
                return Optional.empty();
            }
            return Optional.of(new ProviderUsage(true, fiveHour, weekly));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static WindowUsage window(JsonNode node) {
        JsonNode utilization = node.path("utilization");
        JsonNode resetsAt = node.path("resets_at");
        if (!utilization.isNumber() || !resetsAt.isTextual()) {
            return null;
        }
        try {
            double percentLeft = Math.max(0, 100 - utilization.asDouble());
            return new WindowUsage(percentLeft, OffsetDateTime.parse(resetsAt.asText()).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
