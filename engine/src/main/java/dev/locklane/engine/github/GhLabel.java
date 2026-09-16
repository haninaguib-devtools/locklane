package dev.locklane.engine.github;

/** One label defined in the repo — its name and hex color (no leading {@code #}, matching gh's own JSON). */
public record GhLabel(String name, String color) {
}
