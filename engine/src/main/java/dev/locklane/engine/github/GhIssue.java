package dev.locklane.engine.github;

import java.util.List;

/**
 * One GitHub issue, the fields a sidenav list or issue header needs. {@code parent} is
 * GitHub's native sub-issue relationship (the issue number this one is nested under, or
 * {@code null} for none) — distinct from the body-text {@code Part of: #<n>} convention
 * that predates it (#325).
 */
public record GhIssue(
        int number,
        String title,
        String state,
        List<String> labels,
        String body,
        String createdAt,
        String updatedAt,
        Integer parent) {

    /** Pre-#325 callers with no native parent to report. */
    public GhIssue(int number, String title, String state, List<String> labels, String body, String createdAt,
            String updatedAt) {
        this(number, title, state, labels, body, createdAt, updatedAt, null);
    }
}
