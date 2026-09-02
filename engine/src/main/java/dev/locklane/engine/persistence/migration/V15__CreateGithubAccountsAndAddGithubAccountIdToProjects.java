package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * GitHub accounts (#550) are something Locklane owns rather than something it reads
 * off the host's {@code gh} login: {@code github_accounts} holds each one's login,
 * encrypted OAuth/PAT token, and reported classic scopes, owned by the Locklane user
 * (ADR-105) who added it. {@code projects.github_account_id} — nullable, no account
 * chosen yet — replaces {@code projects.github_token} (#81, V5) as the source of a
 * project's GitHub identity; every read/write of that column is gone from the
 * application by this same task, so the column itself is dropped here too, when the
 * SQLite this runs against supports {@code DROP COLUMN} (added in SQLite 3.35,
 * 2021 — the bundled driver is well past that). A database whose SQLite predates it
 * keeps the column, simply unused from here on, rather than fail the migration.
 */
public class V15__CreateGithubAccountsAndAddGithubAccountIdToProjects extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V15__CreateGithubAccountsAndAddGithubAccountIdToProjects.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS github_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_user_id INTEGER NOT NULL REFERENCES users(id),
                        login TEXT NOT NULL,
                        token TEXT NOT NULL,
                        scopes TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """);
        }

        if (!SqliteColumns.exists(connection, "projects", "github_account_id")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "ALTER TABLE projects ADD COLUMN github_account_id INTEGER REFERENCES github_accounts(id)");
            }
        }

        if (SqliteColumns.exists(connection, "projects", "github_token")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE projects DROP COLUMN github_token");
            } catch (SQLException e) {
                log.info("This SQLite does not support ALTER TABLE ... DROP COLUMN; "
                        + "projects.github_token is left in place, unused from here on", e);
            }
        }
    }
}
