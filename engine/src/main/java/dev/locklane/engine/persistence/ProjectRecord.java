package dev.locklane.engine.persistence;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A project the user added by git URL (#42). {@code defaultBranch} is {@code null}
 * until the checkout succeeds — it is discovered from the clone, not requested.
 *
 * <p>{@code ownerUserId} (#239, ADR-101 Decision 1) is the actual authorization
 * boundary: {@link ProjectController} and {@link ProjectRepository} check it against
 * the authenticated caller (admin excepted) on every read/write. It is never {@code 0}
 * for a row created after {@code V10__AddOwnerUserIdToProjectsAndRelocateWorkareas} —
 * {@link ProjectRepository#create} and {@link ProjectRepository#createReady} both
 * require it explicitly, and that migration backfills every pre-existing row.
 *
 * <p>{@code accentColor} (#427) is {@code null} until the project's owner sets one — a
 * hex string (e.g. {@code "#c15f3c"}) the client derives a lighter background tint
 * from. Unrelated to the global, client-only accent {@code AccentThemeStore} keeps
 * driving the navbar/header outside any project's own pages.
 *
 * <p>{@code template} (#536) is the name of the project template the project was
 * created from — {@code null} for an imported repository, for a project created with
 * no template, and for every row that predates templates. Set once at creation and
 * never edited.
 */
public record ProjectRecord(
        long id,
        long ownerUserId,
        String name,
        String gitUrl,
        Path workareaPath,
        String defaultBranch,
        ProjectStatus status,
        Instant createdAt,
        String accentColor,
        String template) {
}
