package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * template (#536) is the name of the project template a project was created from, or
 * NULL for every project created without one — imported repositories, the engine's
 * own bootstrap checkout, and anything created before templates existed. Set once at
 * creation; #537 reads it to decide whether a project still owes its first seeded
 * console.
 */
public class V13__AddTemplateToProjects extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!SqliteColumns.exists(connection, "projects", "template")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE projects ADD COLUMN template TEXT");
            }
        }
    }
}
