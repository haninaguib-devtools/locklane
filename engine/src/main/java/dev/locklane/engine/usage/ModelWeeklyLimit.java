package dev.locklane.engine.usage;

/**
 * One model's own weekly usage limit, alongside the account-wide weekly window (#288) —
 * e.g. Anthropic's "Fable" cap, reported generically via the {@code limits} array so a
 * future scoped model shows up without further code changes.
 */
public record ModelWeeklyLimit(String modelName, WindowUsage window) {
}
