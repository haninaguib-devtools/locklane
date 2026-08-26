package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * github_token (#81) is NULL until the user stores one; when set, it is a Base64
 * AES-GCM blob (TokenCipher), never plaintext. NULL means issue/PR fetches for this
 * project fall back to whatever `gh` identity is already authenticated for its own
 * checkout directory, same as before this column existed.
 */
public class V5__AddGithubTokenToProjects extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!SqliteColumns.exists(connection, "projects", "github_token")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE projects ADD COLUMN github_token TEXT");
            }
        }
    }
}
