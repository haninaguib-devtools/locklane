package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * template_seeded_at (#537) is the instant a project created from a template (#536)
 * had its one seeded console launched — the agent started with the engine-composed
 * "read PROJECT_TEMPLATE.md and build it" prompt. NULL until then, and forever NULL
 * for a project with no template. Set exactly once, by the WebSocket attach that
 * performed the launch, which is what makes "a READY project with a template and no
 * seeded console yet gets one" a stateless rule that survives reloads.
 */
public class V14__AddTemplateSeededAtToProjects extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!SqliteColumns.exists(connection, "projects", "template_seeded_at")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE projects ADD COLUMN template_seeded_at TEXT");
            }
        }
    }
}
