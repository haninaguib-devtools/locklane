package dev.locklane.engine.persistence;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A worktree's durably persisted session metadata — independent of any live process.
 * {@code ownerUsername} is {@code null} for a session with no recorded owner (#48) —
 * created before per-user ownership existed, or by an unauthenticated attach.
 */
public record WorktreeSessionRecord(
        String worktreeId,
        Path workingDirectory,
        Instant createdAt,
        Instant lastAttachedAt,
        String ownerUsername) {
}
