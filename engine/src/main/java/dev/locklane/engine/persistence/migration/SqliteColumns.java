package dev.locklane.engine.persistence.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite has no {@code ADD COLUMN IF NOT EXISTS} — plain {@code ALTER TABLE ... ADD
 * COLUMN} fails with "duplicate column name" if the column is already there. A
 * database can legitimately already have a column that a later migration also adds:
 * one whose tables were first created (by the old pre-Flyway startup script, or by an
 * earlier run of this same migration history) after that column existed. Every
 * column-adding {@link org.flywaydb.core.api.migration.JavaMigration} in this package
 * checks here first, so it runs correctly exactly once no matter which shape the
 * target database started in.
 */
final class SqliteColumns {

    private SqliteColumns() {
    }

    static boolean exists(Connection connection, String table, String column) throws SQLException {
        // PRAGMA table_info(...) does not accept a bind parameter; the table name
        // always comes from a literal in the migration that calls this, never from
        // user input.
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (rows.getString("name").equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }
}
