package dev.locklane.engine.persistence;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A project the user added by git URL (#42). {@code defaultBranch} is {@code null}
 * until the checkout succeeds — it is discovered from the clone, not requested.
 */
public record ProjectRecord(
        long id,
        String name,
        String gitUrl,
        Path workareaPath,
        String defaultBranch,
        ProjectStatus status,
        Instant createdAt) {
}
