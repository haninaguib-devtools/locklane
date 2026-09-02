package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * {@code sort_order} (#541) is the owner-chosen sidenav position of a project —
 * dragging a row in the sidenav rewrites every affected row's value via
 * {@link dev.locklane.engine.persistence.ProjectRepository#setOrder}. Backfills every
 * pre-existing row to its current implicit order (ascending {@code id}, numbered from 0
 * within each owner) so a project nobody has ever dragged keeps rendering exactly where
 * it always has, rather than jumping to an arbitrary position.
 */
public class V16__AddSortOrderToProjects extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!SqliteColumns.exists(connection, "projects", "sort_order")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE projects ADD COLUMN sort_order INTEGER");
            }
        }
        backfill(connection);
    }

    /** Numbers each owner's rows 0, 1, 2, ... in ascending {@code id} order, leaving any already-numbered row alone. */
    private static void backfill(Connection connection) throws Exception {
        for (long ownerUserId : distinctOwnersMissingOrder(connection)) {
            long position = 0;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM projects WHERE owner_user_id = ? ORDER BY id")) {
                select.setLong(1, ownerUserId);
                try (ResultSet rows = select.executeQuery();
                        PreparedStatement update = connection.prepareStatement(
                                "UPDATE projects SET sort_order = ? WHERE id = ?")) {
                    while (rows.next()) {
                        update.setLong(1, position++);
                        update.setLong(2, rows.getLong("id"));
                        update.executeUpdate();
                    }
                }
            }
        }
    }

    private static java.util.List<Long> distinctOwnersMissingOrder(Connection connection) throws Exception {
        java.util.List<Long> owners = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT DISTINCT owner_user_id FROM projects WHERE sort_order IS NULL "
                                + "AND owner_user_id IS NOT NULL")) {
            while (rs.next()) {
                owners.add(rs.getLong("owner_user_id"));
            }
        }
        return owners;
    }
}
