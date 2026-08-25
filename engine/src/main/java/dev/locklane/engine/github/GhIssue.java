package dev.locklane.engine.github;

import java.util.List;

/** One GitHub issue, the fields a sidenav list or issue header needs. */
public record GhIssue(
        int number,
        String title,
        String state,
        List<String> labels,
        String body,
        String createdAt,
        String updatedAt) {
}
