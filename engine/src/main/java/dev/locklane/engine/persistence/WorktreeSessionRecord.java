package dev.locklane.engine.persistence;

import java.nio.file.Path;
import java.time.Instant;

/** A worktree's durably persisted session metadata — independent of any live process. */
public record WorktreeSessionRecord(
        String worktreeId,
        Path workingDirectory,
        Instant createdAt,
        Instant lastAttachedAt) {
}
