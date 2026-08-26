package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * owner_username (#48) is NULL for a session created before per-user ownership
 * existed, or by an unauthenticated attach (no longer possible since #50 requires
 * auth on the WebSocket endpoint itself, but old rows can still carry it) — treated
 * as unclaimed, visible/attachable by any authenticated user, rather than orphaned.
 */
public class V3__AddOwnerUsernameToWorktreeSessions extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!SqliteColumns.exists(connection, "worktree_sessions", "owner_username")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE worktree_sessions ADD COLUMN owner_username TEXT");
            }
        }
    }
}
