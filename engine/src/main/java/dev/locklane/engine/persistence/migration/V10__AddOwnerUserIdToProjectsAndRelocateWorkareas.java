package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code owner_user_id} (#239, ADR-101 Decision 1) is the actual authorization
 * boundary for a project: every project belongs to exactly one account, and every
 * project read/write path checks this column against the caller's identity (admin
 * excepted) in the application layer — never the filesystem. Left nullable at the
 * column level — SQLite's {@code ALTER TABLE ... ADD COLUMN} cannot add a
 * {@code NOT NULL} column without a static default, and the backfill target (the
 * admin's id) isn't known until this migration runs — but
 * {@link dev.locklane.engine.persistence.ProjectRepository#create} and
 * {@code #createReady} both require it explicitly, so no row created after this
 * migration is ever left without an owner.
 *
 * <p>Backfills every pre-existing row to the bootstrapped admin account (the
 * lowest-id row in {@code users} with {@code role = 'ADMIN'}), same rationale as
 * #238's own backfill of {@code role} to {@code ADMIN}: an already-running
 * single-user install keeps every project it already had. A database with no admin
 * yet (a brand-new install where {@code UserBootstrapper} has not run) has no
 * pre-existing projects to backfill either, since project creation has always
 * required an authenticated caller — that case is a no-op.
 *
 * <p>Also relocates each backfilled project's on-disk workarea directory from
 * {@code <parent>/<slug>} to {@code <parent>/<owner_user_id>/<slug>} (ADR-101
 * Decision 2), so the layout on disk matches what {@code ProjectCheckoutService}
 * writes for every new project from here on. Safe to interrupt and rerun: only rows
 * still missing an owner are considered, the move is skipped (not an error) when the
 * source directory no longer exists (nothing was ever checked out, or it was already
 * moved by an earlier attempt) or the destination already exists (an earlier attempt
 * already moved it but crashed before recording it), and a single directory rename
 * never partially copies — it either lands whole or not at all.
 */
public class V10__AddOwnerUserIdToProjectsAndRelocateWorkareas extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            if (!SqliteColumns.exists(connection, "projects", "owner_user_id")) {
                statement.execute("ALTER TABLE projects ADD COLUMN owner_user_id INTEGER REFERENCES users(id)");
            }
        }

        Long adminId = findAdminId(connection);
        if (adminId == null) {
            return;
        }

        for (ProjectRow row : findRowsMissingOwner(connection)) {
            Path oldPath = Path.of(row.workareaPath());
            Path newPath = relocated(oldPath, adminId);
            relocateIfNeeded(oldPath, newPath);
            backfill(connection, row.id(), adminId, newPath.toString());
        }
    }

    private static Long findAdminId(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1")) {
            return rs.next() ? rs.getLong("id") : null;
        }
    }

    private static List<ProjectRow> findRowsMissingOwner(Connection connection) throws Exception {
        List<ProjectRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT id, workarea_path FROM projects WHERE owner_user_id IS NULL")) {
            while (rs.next()) {
                rows.add(new ProjectRow(rs.getLong("id"), rs.getString("workarea_path")));
            }
        }
        return rows;
    }

    private static void backfill(Connection connection, long projectId, long ownerUserId, String newWorkareaPath)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE projects SET owner_user_id = ?, workarea_path = ? WHERE id = ?")) {
            statement.setLong(1, ownerUserId);
            statement.setString(2, newWorkareaPath);
            statement.setLong(3, projectId);
            statement.executeUpdate();
        }
    }

    /** {@code <parent>/<slug>} -> {@code <parent>/<ownerUserId>/<slug>}, per ADR-101 Decision 2. */
    private static Path relocated(Path oldPath, long ownerUserId) {
        Path parent = oldPath.getParent();
        Path leaf = oldPath.getFileName();
        return (parent == null ? Path.of(".") : parent).resolve(String.valueOf(ownerUserId)).resolve(leaf);
    }

    private static void relocateIfNeeded(Path oldPath, Path newPath) throws IOException {
        if (oldPath.equals(newPath) || !Files.exists(oldPath) || Files.exists(newPath)) {
            return;
        }
        Files.createDirectories(newPath.getParent());
        Files.move(oldPath, newPath);
    }

    private record ProjectRow(long id, String workareaPath) {
    }
}
