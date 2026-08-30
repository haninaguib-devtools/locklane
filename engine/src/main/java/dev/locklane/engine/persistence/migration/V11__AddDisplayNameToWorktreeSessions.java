package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * display_name (#393) is the name a user typed for a console tab, overriding the
 * auto-generated label the client derives from the session's id and agent. NULL —
 * the value every session created before this migration carries, and the value a
 * cleared name is stored back as — means "no custom name", so the client falls back
 * to that auto label.
 */
public class V11__AddDisplayNameToWorktreeSessions extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!SqliteColumns.exists(connection, "worktree_sessions", "display_name")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE worktree_sessions ADD COLUMN display_name TEXT");
            }
        }
    }
}
