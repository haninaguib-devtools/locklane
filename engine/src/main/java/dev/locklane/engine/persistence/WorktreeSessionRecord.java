package dev.locklane.engine.persistence;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A worktree's durably persisted session metadata — independent of any live process.
 * {@code ownerUsername} records who first attached to the session ({@code null} for
 * one with no recorded owner — created before per-user ownership existed, or by an
 * unauthenticated attach) but, since #242 (ADR-007 Decision 6), is purely
 * informational: who may view or attach to the session is decided from its owning
 * project's {@code owner_user_id} instead — see {@link WorktreeSessionAuthorization}
 * — never from this column.
 */
public record WorktreeSessionRecord(
        String worktreeId,
        Path workingDirectory,
        Instant createdAt,
        Instant lastAttachedAt,
        String ownerUsername) {
}
