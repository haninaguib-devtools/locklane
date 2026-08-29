package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * role (#238) distinguishes an administrator from an ordinary user. Backfilling every
 * pre-existing row to {@code 'ADMIN'} (via the column {@code DEFAULT} below) exists
 * only to preserve access for an already-running single-user install — it is not a
 * decision about the role of any row created after this migration has already run
 * once. {@link dev.locklane.engine.persistence.UserRepository#create} always passes
 * {@code role} explicitly rather than relying on that default.
 *
 * <p>must_change_password (#238, needed by #240's forced-first-login flow) marks an
 * admin-created account that has to set its own password before it can use the app.
 * Every existing and newly created account defaults to {@code false} until #240 adds
 * a way to set it {@code true}.
 */
public class V9__AddRoleAndMustChangePasswordToUsers extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            if (!SqliteColumns.exists(connection, "users", "role")) {
                statement.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'ADMIN'");
            }
            if (!SqliteColumns.exists(connection, "users", "must_change_password")) {
                statement.execute("ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0");
            }
        }
    }
}
