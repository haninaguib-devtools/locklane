package dev.locklane.engine.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>The response also carries a generic {@code limits} array (#288) — confirmed live
 * with an entry whose {@code group} is {@code "weekly"} and whose {@code scope.model}
 * names a model (e.g. {@code display_name: "Fable"}) that gets its own weekly quota
 * separate from the account-wide {@code seven_day} figure. Every such entry is read into
 * {@link ProviderUsage#modelWeeklyLimits()}; any other {@code limits} entry, and every
 * other top-level field the response happens to carry (this account's response also has
 * several null/undocumented ones), is ignored rather than failing the parse.
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
    public String id() {
        return "claude";
    }

    @Override
    public String label() {
        return "Claude";
    }

    @Override
    public String color() {
        return "var(--green)";
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
            return Optional.of(new ProviderUsage(true, fiveHour, weekly, modelWeeklyLimits(root)));
        } catch (IOException e) {
            // silent: an undocumented endpoint changing shape degrades to
            // unavailable, per this class's own doc — never a broken sidebar.
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
            // silent: same "unexpected shape degrades gracefully" reasoning as above.
            return null;
        }
    }

    private static List<ModelWeeklyLimit> modelWeeklyLimits(JsonNode root) {
        List<ModelWeeklyLimit> limits = new ArrayList<>();
        for (JsonNode entry : root.path("limits")) {
            JsonNode group = entry.path("group");
            JsonNode displayName = entry.path("scope").path("model").path("display_name");
            WindowUsage window = window(entry);
            if (group.isTextual() && "weekly".equals(group.asText()) && displayName.isTextual() && window != null) {
                limits.add(new ModelWeeklyLimit(displayName.asText(), window));
            }
        }
        return limits;
    }
}
