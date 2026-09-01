package dev.locklane.engine.github;

/**
 * One GitHub account {@code gh} is logged into on the engine host (#532) — its login
 * and whether it is the host's currently active account for {@code github.com}.
 */
public record GhAccount(String login, boolean active) {
}
