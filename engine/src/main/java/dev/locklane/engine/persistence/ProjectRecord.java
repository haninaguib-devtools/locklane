package dev.locklane.engine.persistence;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A project the user added by git URL (#42). {@code defaultBranch} is {@code null}
 * until the checkout succeeds — it is discovered from the clone, not requested.
 *
 * <p>{@code ownerUserId} (#239, ADR-007 Decision 1) is the actual authorization
 * boundary: {@link ProjectController} and {@link ProjectRepository} check it against
 * the authenticated caller (admin excepted) on every read/write. It is never {@code 0}
 * for a row created after {@code V10__AddOwnerUserIdToProjectsAndRelocateWorkareas} —
 * {@link ProjectRepository#create} and {@link ProjectRepository#createReady} both
 * require it explicitly, and that migration backfills every pre-existing row.
 */
public record ProjectRecord(
        long id,
        long ownerUserId,
        String name,
        String gitUrl,
        Path workareaPath,
        String defaultBranch,
        ProjectStatus status,
        Instant createdAt) {
}
