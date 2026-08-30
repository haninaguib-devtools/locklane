package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * accent_color (#427) is NULL until a project's owner sets one — a project with no
 * accent color of its own is tinted with nothing, distinct from the global,
 * client-only accent this column has no relationship to.
 */
public class V12__AddAccentColorToProjects extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!SqliteColumns.exists(connection, "projects", "accent_color")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE projects ADD COLUMN accent_color TEXT");
            }
        }
    }
}
