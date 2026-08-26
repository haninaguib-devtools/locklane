package dev.locklane.engine.persistence.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.Statement;

/**
 * totp_secret (#88) is NULL until the user starts 2FA enrollment; when set, it is a
 * Base64 AES-GCM blob (TokenCipher) wrapping the Base32 TOTP secret, never plaintext.
 * totp_enabled distinguishes an enrollment that was started from one that was proved:
 * a secret with totp_enabled = 0 is pending (the user has scanned it but not yet
 * entered a matching code), and only totp_enabled = 1 means 2FA is actually on.
 * Disabling clears both back to NULL / 0.
 */
public class V6__AddTotpColumnsToUsers extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            if (!SqliteColumns.exists(connection, "users", "totp_secret")) {
                statement.execute("ALTER TABLE users ADD COLUMN totp_secret TEXT");
            }
            if (!SqliteColumns.exists(connection, "users", "totp_enabled")) {
                statement.execute("ALTER TABLE users ADD COLUMN totp_enabled INTEGER NOT NULL DEFAULT 0");
            }
        }
    }
}
